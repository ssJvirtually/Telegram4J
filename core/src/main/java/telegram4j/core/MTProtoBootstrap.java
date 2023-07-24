package telegram4j.core;

import org.reactivestreams.Publisher;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import telegram4j.core.auth.AuthorizationHandler;
import telegram4j.core.event.DefaultEventDispatcher;
import telegram4j.core.event.DefaultUpdatesManager;
import telegram4j.core.event.EventDispatcher;
import telegram4j.core.event.UpdatesManager;
import telegram4j.core.event.dispatcher.UpdatesMapper;
import telegram4j.core.event.domain.Event;
import telegram4j.core.internal.ParkEmitFailureHandler;
import telegram4j.core.retriever.EntityRetrievalStrategy;
import telegram4j.core.retriever.EntityRetriever;
import telegram4j.core.util.Id;
import telegram4j.core.util.parser.EntityParserFactory;
import telegram4j.mtproto.*;
import telegram4j.mtproto.auth.DhPrimeChecker;
import telegram4j.mtproto.auth.DhPrimeCheckerCache;
import telegram4j.mtproto.client.*;
import telegram4j.mtproto.resource.TcpClientResources;
import telegram4j.mtproto.service.ServiceHolder;
import telegram4j.mtproto.store.FileStoreLayout;
import telegram4j.mtproto.store.StoreLayout;
import telegram4j.mtproto.store.StoreLayoutImpl;
import telegram4j.mtproto.transport.IntermediateTransport;
import telegram4j.mtproto.transport.Transport;
import telegram4j.mtproto.transport.TransportFactory;
import telegram4j.tl.*;
import telegram4j.tl.auth.Authorization;
import telegram4j.tl.auth.BaseAuthorization;
import telegram4j.tl.request.InitConnection;
import telegram4j.tl.request.InvokeWithLayer;
import telegram4j.tl.request.auth.ImmutableImportBotAuthorization;
import telegram4j.tl.request.help.GetConfig;
import telegram4j.tl.request.users.ImmutableGetUsers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.function.Function;

import static telegram4j.mtproto.internal.Preconditions.requireArgument;

public final class MTProtoBootstrap {

    private static final Logger log = Loggers.getLogger(MTProtoBootstrap.class);

    private final AuthorizationResources authResources;
    @Nullable
    private final AuthorizationHandler authHandler;
    private final List<ResponseTransformer> responseTransformers = new ArrayList<>();

    private TransportFactory transportFactory = dc -> new IntermediateTransport(true);
    private BiFunction<MTProtoOptions, MTProtoClient.Options, ClientFactory> clientFactory = DefaultClientFactory::new;

    @Nullable
    private EntityParserFactory defaultEntityParserFactory;
    private EntityRetrievalStrategy entityRetrievalStrategy = EntityRetrievalStrategy.STORE_FALLBACK_RPC;
    private Function<MTProtoTelegramClient, UpdatesManager> updatesManagerFactory = c ->
            new DefaultUpdatesManager(c, new DefaultUpdatesManager.Options(c));
    private Function<MTProtoClientGroup.Options, MTProtoClientGroup> clientGroupFactory = options ->
            new DefaultMTProtoClientGroup(new DefaultMTProtoClientGroup.Options(options));
    private DhPrimeChecker dhPrimeChecker;
    private PublicRsaKeyRegister publicRsaKeyRegister;
    private DcOptions dcOptions;
    private InitConnectionParams initConnectionParams;
    private StoreLayout storeLayout;
    private EventDispatcher eventDispatcher;
    private DataCenter dataCenter;
    private int gzipCompressionSizeThreshold = 16 * 1024;
    private TcpClientResources tcpClientResources;
    private UpdateDispatcher updateDispatcher;
    private Duration pingInterval = Duration.ofSeconds(10);
    private Duration authKeyLifetime = Duration.ofDays(1);
    // Max backoff is 16 seconds
    private ReconnectionStrategy reconnectionStrategy = DefaultReconnectionStrategy.create(3, 5, Duration.ofSeconds(1));

    private ExecutorService resultPublisher;
    private Scheduler updatesPublisher;

    MTProtoBootstrap(AuthorizationResources authResources, @Nullable AuthorizationHandler authHandler) {
        this.authResources = authResources;
        this.authHandler = authHandler;
    }

    /**
     * Sets the factory of client group for working with different datacenters and sessions.
     * <p>
     * If custom implementation doesn't set, {@link DefaultMTProtoClientGroup} will be used.
     *
     * @param clientGroupFactory A new factory for client group.
     * @return This builder.
     */
    public MTProtoBootstrap setClientGroupManager(Function<MTProtoClientGroup.Options, MTProtoClientGroup> clientGroupFactory) {
        this.clientGroupFactory = Objects.requireNonNull(clientGroupFactory);
        return this;
    }

    /**
     * Sets store layout for accessing and persisting incoming data from Telegram API.
     * <p>
     * If custom implementation doesn't set, {@link StoreLayoutImpl} with message LRU cache bounded to {@literal 10000} will be used.
     *
     * @param storeLayout A new store layout implementation for client.
     * @return This builder.
     */
    public MTProtoBootstrap setStoreLayout(StoreLayout storeLayout) {
        this.storeLayout = Objects.requireNonNull(storeLayout);
        return this;
    }

    /**
     * Sets TCP transport factory for all MTProto clients.
     * <p>
     * If custom transport factory doesn't set, {@link IntermediateTransport} factory will be used as threshold.
     *
     * @param transportFactory A new {@link Transport} factory for clients.
     * @return This builder.
     * @see <a href="https://core.telegram.org/mtproto/mtproto-transports">MTProto Transport</a>
     */
    public MTProtoBootstrap setTransportFactory(TransportFactory transportFactory) {
        this.transportFactory = Objects.requireNonNull(transportFactory);
        return this;
    }

    /**
     * Sets TCP client resources for all MTProto clients.
     * <p>
     * If custom resources doesn't set, clients will use {@link TcpClientResources#create()}.
     *
     * @param tcpClientResources A new {@link TcpClientResources} for clients.
     * @return This builder.
     */
    public MTProtoBootstrap setTcpClientResources(TcpClientResources tcpClientResources) {
        this.tcpClientResources = Objects.requireNonNull(tcpClientResources);
        return this;
    }

    /**
     * Sets connection identity parameters.
     * That parameters send on connection establishment, i.e. sending {@link InitConnection} request.
     * <p>
     * If custom parameters doesn't set, {@link InitConnectionParams#getDefault()} will be used.
     *
     * @param initConnectionParams A new connection identity parameters.
     * @return This builder.
     */
    public MTProtoBootstrap setInitConnectionParams(InitConnectionParams initConnectionParams) {
        this.initConnectionParams = Objects.requireNonNull(initConnectionParams);
        return this;
    }

    /**
     * Sets custom {@link EventDispatcher} implementation for distributing mapped {@link Event events} to subscribers.
     * <p>
     * If custom event dispatcher doesn't set, {@link Sinks sinks}-based {@link DefaultEventDispatcher} implementation will be used.
     *
     * @param eventDispatcher A new event dispatcher.
     * @return This builder.
     */
    public MTProtoBootstrap setEventDispatcher(EventDispatcher eventDispatcher) {
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher);
        return this;
    }

    /**
     * Sets custom {@link ExecutorService} for requests callback handling.
     * <p>
     * If no custom {@code ExecutorService} specified, {@link ForkJoinPool#commonPool()} will be used.
     *
     * @param resultPublisher A new {@code ExecutorService} for handling RPC results.
     * @return This builder.
     */
    public MTProtoBootstrap setResultPublisher(ExecutorService resultPublisher) {
        this.resultPublisher = Objects.requireNonNull(resultPublisher);
        return this;
    }

    public MTProtoBootstrap setUpdatesPublisher(Scheduler updatesPublisher) {
        this.updatesPublisher = updatesPublisher;
        return this;
    }

    // TODO docs
    public MTProtoBootstrap setUpdateDispatcher(UpdateDispatcher updateDispatcher) {
        this.updateDispatcher = Objects.requireNonNull(updateDispatcher);
        return this;
    }

    public MTProtoBootstrap setReconnectionStrategy(ReconnectionStrategy reconnectionStrategy) {
        this.reconnectionStrategy = Objects.requireNonNull(reconnectionStrategy);
        return this;
    }

    /** @deprecated use {@link #setReconnectionStrategy(ReconnectionStrategy)} with {@link ReconnectionStrategy#fixedInterval(Duration)} */
    @Deprecated(forRemoval = true)
    public MTProtoBootstrap setReconnectionInterval(Duration reconnectionInterval) {
        this.reconnectionStrategy = ReconnectionStrategy.fixedInterval(reconnectionInterval);
        return this;
    }

    public MTProtoBootstrap setAuthKeyLifetime(Duration authKeyLifetime) {
        requireArgument(!authKeyLifetime.isNegative());
        this.authKeyLifetime = authKeyLifetime;
        return this;
    }

    public MTProtoBootstrap setPingInterval(Duration pingInterval) {
        requireArgument(!pingInterval.isNegative());
        this.pingInterval = pingInterval;
        return this;
    }

    /**
     * Sets default DC address for main MTProto client.
     *
     * <p> If DC address doesn't set, production IPv4 DC 2 (europe) will be used.
     * This DC will be used only if local store have no information.
     *
     * @param dataCenter A new DC address to use.
     * @return This builder.
     * @throws IllegalArgumentException if type of specified option is not {@link DataCenter.Type#REGULAR}.
     */
    public MTProtoBootstrap setDataCenter(DataCenter dataCenter) {
        requireArgument(dataCenter.getType() == DataCenter.Type.REGULAR, "Invalid type for main DC");
        this.dataCenter = dataCenter;
        return this;
    }

    /**
     * Sets updates manager factory for creating updates manager.
     * <p>
     * If custom updates manager factory doesn't set, {@link UpdatesMapper} will be used.
     *
     * @param updatesManagerFactory A new factory for creating {@link UpdatesManager}.
     * @return This builder.
     */
    public MTProtoBootstrap setUpdatesManager(Function<MTProtoTelegramClient, UpdatesManager> updatesManagerFactory) {
        this.updatesManagerFactory = Objects.requireNonNull(updatesManagerFactory);
        return this;
    }

    /**
     * Sets entity retrieval strategy factory for creating default entity retriever.
     * <p>
     * If custom entity retrieval strategy doesn't set, {@link EntityRetrievalStrategy#STORE_FALLBACK_RPC} will be used.
     *
     * @param strategy A new default strategy for creating {@link EntityRetriever}.
     * @return This builder.
     */
    public MTProtoBootstrap setEntityRetrieverStrategy(EntityRetrievalStrategy strategy) {
        this.entityRetrievalStrategy = Objects.requireNonNull(strategy);
        return this;
    }

    /**
     * Sets default global {@link EntityParserFactory} for text parsing, by default is {@code null}.
     *
     * @param defaultEntityParserFactory A new default {@link EntityParserFactory} for text parsing.
     * @return This builder.
     */
    public MTProtoBootstrap setDefaultEntityParserFactory(EntityParserFactory defaultEntityParserFactory) {
        this.defaultEntityParserFactory = Objects.requireNonNull(defaultEntityParserFactory);
        return this;
    }

    /**
     * Sets register with known public RSA keys, needed for auth key generation,
     * by default {@link PublicRsaKeyRegister#createDefault()} will be used.
     *
     * <p> This register will be used only if {@link StoreLayout} have no keys.
     *
     * @param publicRsaKeyRegister A new register with known public RSA keys.
     * @return This builder.
     */
    public MTProtoBootstrap setPublicRsaKeyRegister(PublicRsaKeyRegister publicRsaKeyRegister) {
        this.publicRsaKeyRegister = Objects.requireNonNull(publicRsaKeyRegister);
        return this;
    }

    /**
     * Sets DH prime register with known primes, needed for auth key generation,
     * by default the common {@link DhPrimeCheckerCache#instance()} will be used.
     *
     * @param dhPrimeChecker A new prime checker.
     * @return This builder.
     */
    public MTProtoBootstrap setDhPrimeChecker(DhPrimeChecker dhPrimeChecker) {
        this.dhPrimeChecker = Objects.requireNonNull(dhPrimeChecker);
        return this;
    }

    /**
     * Sets list of known dc options, used in connection establishment,
     * by default {@link DcOptions#createDefault(boolean, boolean)} will be used.
     *
     * <p> This options will be used only if {@link StoreLayout} have no options.
     *
     * @param dcOptions A new list of known dc options.
     * @return This builder.
     */
    public MTProtoBootstrap setDcOptions(DcOptions dcOptions) {
        this.dcOptions = Objects.requireNonNull(dcOptions);
        return this;
    }

    /**
     * Adds new {@link ResponseTransformer} to transformation list.
     *
     * @param responseTransformer The new {@link ResponseTransformer} to add.
     * @return This builder.
     */
    public MTProtoBootstrap addResponseTransformer(ResponseTransformer responseTransformer) {
        responseTransformers.add(Objects.requireNonNull(responseTransformer));
        return this;
    }

    /**
     * Sets size threshold for gzip packing mtproto queries, by default equals to 16KB.
     *
     * @throws IllegalArgumentException if {@code gzipCompressionSizeThreshold} is negative.
     * @param gzipCompressionSizeThreshold The new request's size threshold.
     * @return This builder.
     */
    public MTProtoBootstrap setGzipCompressionSizeThreshold(int gzipCompressionSizeThreshold) {
        requireArgument(gzipCompressionSizeThreshold > 0, "gzipCompressionSizeThreshold must be positive");
        this.gzipCompressionSizeThreshold = gzipCompressionSizeThreshold;
        return this;
    }

    /**
     * Sets client factory for creating mtproto clients, by default {@link DefaultClientFactory} is used.
     *
     * @param clientFactory The new client factory constructor.
     * @return This builder.
     */
    public MTProtoBootstrap setClientFactory(BiFunction<MTProtoOptions, MTProtoClient.Options, ClientFactory> clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
        return this;
    }

    /**
     * Prepare and connect {@link MTProtoClient} to the specified Telegram DC and
     * on successfully completion emit {@link MTProtoTelegramClient} to subscribers.
     * Any errors caused on connection time will be emitted to a {@link Mono} and terminate client with disconnecting.
     *
     * @param func A function to use client until it's disconnected.
     * @return A {@link Mono} that upon subscription and successfully completion emits a {@link MTProtoTelegramClient}.
     * @see #connect()
     */
    public Mono<Void> withConnection(Function<MTProtoTelegramClient, ? extends Publisher<?>> func) {
        return Mono.usingWhen(connect(), client -> Flux.from(func.apply(client)).then(client.onDisconnect()),
                MTProtoTelegramClient::disconnect);
    }

    /**
     * Prepare and connect MTProto client to the specified Telegram DC and
     * on successfully completion emit {@link MTProtoTelegramClient} to subscribers.
     * Any errors caused on connection time will be emitted to a {@link Mono}.
     *
     * @return A {@link Mono} that upon subscription and successfully completion emits a {@link MTProtoTelegramClient}.
     */
    public Mono<MTProtoTelegramClient> connect() {
        return Mono.create(sink -> {
            StoreLayout storeLayout = initStoreLayout();

            var composite = Disposables.composite();
            composite.add(storeLayout.initialize()
                    // Here the subscription order is important
                    // and therefore need to make sure that initialization blocks
                    .subscribeOn(Schedulers.immediate())
                    .subscribe(null, t -> log.error("Store layout terminated with an error", t)));

            var loadDcOptions = storeLayout.getDcOptions()
                    .switchIfEmpty(Mono.defer(() -> { // no DcOptions present in store - use default
                        var dcOptions = initDcOptions();
                        return storeLayout.updateDcOptions(dcOptions)
                                .thenReturn(dcOptions);
                    }));
            var loadMainDc = loadDcOptions
                    .zipWhen(dcOptions -> storeLayout.getDataCenter()
                            .switchIfEmpty(Mono.fromSupplier(() -> initDataCenter(dcOptions))));

            composite.add(loadMainDc
                    .flatMap(TupleUtils.function((dcOptions, mainDc) -> {
                        var tcpClientResources = initTcpClientResources();
                        var initConnectionRequest = InvokeWithLayer.<Config, InitConnection<Config, GetConfig>>builder()
                                .layer(TlInfo.LAYER)
                                .query(initConnection())
                                .build();
                        var responseTransformers = List.copyOf(this.responseTransformers);
                        var clientOptions = new MTProtoClient.Options(
                                transportFactory, initConnectionRequest,
                                pingInterval, reconnectionStrategy,
                                gzipCompressionSizeThreshold, responseTransformers, authKeyLifetime);
                        var mtProtoOptions = new MTProtoOptions(
                                tcpClientResources, initPublicRsaKeyRegister(),
                                initDhPrimeChecker(), storeLayout,
                                initResultPublisher());

                        var updatesScheduler = initUpdatesPublisher();
                        ClientFactory clientFactory = this.clientFactory.apply(mtProtoOptions, clientOptions);
                        MTProtoClientGroup clientGroup = clientGroupFactory.apply(
                                MTProtoClientGroup.Options.of(mainDc, clientFactory,
                                        initUpdateDispatcher(updatesScheduler), mtProtoOptions));

                        return authorizeClient(clientGroup, storeLayout, dcOptions)
                                .flatMap(selfId -> initializeClient(selfId, clientGroup, mtProtoOptions, updatesScheduler));
                    }))
                    .doOnSuccess(sink::success)
                    .subscribe(null, sink::error));

            sink.onCancel(composite);
        });
    }

    private Mono<Id> authorizeClient(MTProtoClientGroup clientGroup,
                                     StoreLayout storeLayout, DcOptions dcOptions) {
        return Mono.create(sink -> {
            var mainClient = clientGroup.main();

            sink.onCancel(mainClient.connect()
                    .onErrorResume(e -> clientGroup.close()
                            .doOnSuccess(any -> sink.error(e))
                            .then(Mono.never()))
                    .then(Mono.defer(() -> {
                        if (authHandler != null) {
                            // to trigger user auth
                            return mainClient.send(ImmutableGetUsers.of(List.of(InputUserSelf.instance())))
                                    .doOnNext(ign -> sink.success(extractSelfId(ign.get(0))))
                                    .onErrorResume(RpcException.isErrorCode(401), t ->
                                            authHandler.process(new AuthorizationHandler.Resources(clientGroup, storeLayout, authResources))
                                            // users can emit empty signals if they want to gracefully destroy the client
                                            .switchIfEmpty(Mono.defer(clientGroup::close)
                                                    .then(Mono.fromRunnable(sink::success)))
                                            .flatMap(auth -> storeLayout.onAuthorization(auth)
                                                    .doOnSuccess(ign -> sink.success(extractSelfId(auth.user()))))
                                            .then(Mono.empty()));
                        }
                        return mainClient.send(ImmutableImportBotAuthorization.of(0,
                                        authResources.getApiId(), authResources.getApiHash(),
                                        authResources.getBotAuthToken().orElseThrow()))
                                .onErrorResume(RpcException.isErrorCode(303),
                                        e -> redirectToDc((RpcException) e, mainClient,
                                                clientGroup, storeLayout, dcOptions))
                                .cast(BaseAuthorization.class)
                                .flatMap(auth -> storeLayout.onAuthorization(auth)
                                        .doOnSuccess(ign -> sink.success(extractSelfId(auth.user()))));
                    }))
                    .onErrorResume(e -> clientGroup.close()
                            .doOnSuccess(any -> sink.error(e)))
                    .subscribe());
        });
    }

    private static Id extractSelfId(User user) {
        if (!(user instanceof BaseUser b)) {
            throw new IllegalStateException("Unexpected type of user from auth result");
        }
        return Id.ofUser(b.id(), b.accessHash());
    }

    private Mono<MTProtoTelegramClient> initializeClient(Id selfId, MTProtoClientGroup clientGroup,
                                                         MTProtoOptions options, Scheduler updatesScheduler) {
        return Mono.create(sink -> {
            EventDispatcher eventDispatcher = initEventDispatcher(updatesScheduler);
            Sinks.Empty<Void> onDisconnect = Sinks.empty();

            MTProtoResources mtProtoResources = new MTProtoResources(
                    options.storeLayout(), eventDispatcher, defaultEntityParserFactory);
            ServiceHolder serviceHolder = new ServiceHolder(clientGroup, options.storeLayout());

            MTProtoTelegramClient telegramClient = new MTProtoTelegramClient(
                    authResources, clientGroup,
                    mtProtoResources, updatesManagerFactory, selfId,
                    serviceHolder, entityRetrievalStrategy, onDisconnect.asMono());

            var composite = Disposables.composite();

            composite.add(clientGroup.start()
                    .doOnError(sink::error)
                    .subscribe(null, t -> log.error("MTProto client group terminated with an error", t)));

            composite.add(clientGroup.updates().all()
                    .flatMap(telegramClient.getUpdatesManager()::handle)
                    .doOnNext(eventDispatcher::publish)
                    .subscribe(null, t -> log.error("Event dispatcher terminated with an error", t)));

            composite.add(telegramClient.getUpdatesManager().start()
                    .subscribe(null, t -> log.error("Updates manager terminated with an error", t)));

            composite.add(clientGroup.main().onClose()
                    .doOnSuccess(any -> {
                        eventDispatcher.shutdown();
                        telegramClient.getUpdatesManager().shutdown();
                        options.storeLayout().close().block();
                        onDisconnect.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
                    })
                    .subscribe(null, t -> log.error("Exception while closing main client", t)));

            composite.add(Mono.defer(() -> {
                        sink.success(telegramClient);
                        return telegramClient.getUpdatesManager().fillGap();
                    })
                    .subscribe(null, t -> log.error("Exception while preparing client resources", t)));

            sink.onCancel(composite);
        });
    }

    private Mono<Authorization> redirectToDc(RpcException rpcExc,
                                             MTProtoClient tmpClient, MTProtoClientGroup clientGroup,
                                             StoreLayout storeLayout, DcOptions dcOptions) {
        return Mono.defer(() -> {
            String msg = rpcExc.getError().errorMessage();
            if (!msg.startsWith("USER_MIGRATE_"))
                return Mono.error(new IllegalStateException("Unexpected type of DC redirection", rpcExc));

            int dcId = Integer.parseInt(msg.substring(13));
            log.info("Redirecting to the DC {}", dcId);

            return Mono.justOrEmpty(dcOptions.find(DataCenter.Type.REGULAR, dcId))
                    // We used default DcOptions which may be outdated.
                    // Well, let's request dc config and store it
                    .switchIfEmpty(tmpClient.send(GetConfig.instance())
                            .flatMap(cfg -> storeLayout.onUpdateConfig(cfg)
                                    .then(storeLayout.getDcOptions()))
                            .flatMap(newOpts -> Mono.justOrEmpty(newOpts.find(DataCenter.Type.REGULAR, dcId))
                                    .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                                            "Could not find DC " + dcId + " for redirecting main client in received options: " + newOpts)))))
                    .flatMap(clientGroup::setMain)
                    .flatMap(client -> client.send(ImmutableImportBotAuthorization.of(0,
                            authResources.getApiId(), authResources.getApiHash(),
                            authResources.getBotAuthToken().orElseThrow())));
        });
    }

    // Resources initialization
    // ==========================

    private InitConnection<Config, GetConfig> initConnection() {
        InitConnectionParams params = initConnectionParams != null
                ? initConnectionParams
                : InitConnectionParams.getDefault();

        var initConnection = InitConnection.<Config, GetConfig>builder()
                .apiId(authResources.getApiId())
                .appVersion(params.getAppVersion())
                .deviceModel(params.getDeviceModel())
                .langCode(params.getLangCode())
                .langPack(params.getLangPack())
                .systemVersion(params.getSystemVersion())
                .systemLangCode(params.getSystemLangCode())
                .query(GetConfig.instance());

        params.getProxy().ifPresent(initConnection::proxy);
        params.getParams().ifPresent(initConnection::params);

        return initConnection.build();
    }

    private PublicRsaKeyRegister initPublicRsaKeyRegister() {
        if (publicRsaKeyRegister != null) {
            return publicRsaKeyRegister;
        }
        return PublicRsaKeyRegister.createDefault();
    }

    private TcpClientResources initTcpClientResources() {
        if (tcpClientResources != null) {
            return tcpClientResources;
        }
        return TcpClientResources.create();
    }

    private Scheduler initUpdatesPublisher() {
        if (updatesPublisher != null) {
            return updatesPublisher;
        }
        return Schedulers.newParallel("t4j-events", 4, true);
    }

    private EventDispatcher initEventDispatcher(Scheduler updatesPublisher) {
        if (eventDispatcher != null) {
            return eventDispatcher;
        }
        return new DefaultEventDispatcher(updatesPublisher,
                Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false),
                new ParkEmitFailureHandler(100));
    }

    private UpdateDispatcher initUpdateDispatcher(Scheduler updatesPublisher) {
        if (updateDispatcher != null) {
            return updateDispatcher;
        }
        return new SinksUpdateDispatcher(updatesPublisher,
                Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false),
                new ParkEmitFailureHandler(100));
    }

    private DhPrimeChecker initDhPrimeChecker() {
        if (dhPrimeChecker != null) {
            return dhPrimeChecker;
        }
        return DhPrimeCheckerCache.instance();
    }

    private StoreLayout initStoreLayout() {
        if (storeLayout != null) {
            return storeLayout;
        }
        return new FileStoreLayout(new StoreLayoutImpl(c -> c.maximumSize(1000)));
    }

    private DataCenter initDataCenter(DcOptions opts) {
        if (dataCenter != null) {
            return dataCenter;
        }
        return opts.findFirst(DataCenter.Type.REGULAR)
                .orElseThrow(() -> new IllegalStateException("Could not find any regular DC for main client in options: " + opts));
    }

    private DcOptions initDcOptions() {
        if (dcOptions != null) {
            return dcOptions;
        }
        return DcOptions.createDefault(false);
    }

    private ExecutorService initResultPublisher() {
        if (resultPublisher != null) {
            return resultPublisher;
        }
        return ForkJoinPool.commonPool();
    }
}
