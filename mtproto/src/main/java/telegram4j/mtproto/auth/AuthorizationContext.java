package telegram4j.mtproto.auth;

import io.netty.buffer.ByteBuf;
import telegram4j.mtproto.PublicRsaKeyRegister;
import telegram4j.tl.mtproto.ServerDHParams;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.ReferenceCountUtil.safeRelease;

/** Holder object used during authorization key generation. */
public final class AuthorizationContext {
    private final DhPrimeChecker dhPrimeChecker;
    private final PublicRsaKeyRegister publicRsaKeyRegister;

    private volatile ByteBuf nonce;
    private volatile ByteBuf newNonce;
    private volatile ByteBuf serverNonce;
    private volatile ByteBuf authKey;
    private volatile long serverSalt;
    private volatile ByteBuf authKeyHash;
    private volatile ServerDHParams serverDHParams;
    private volatile int serverTimeDiff;
    private final AtomicInteger retry = new AtomicInteger();

    public AuthorizationContext(DhPrimeChecker dhPrimeChecker, PublicRsaKeyRegister publicRsaKeyRegister) {
        this.dhPrimeChecker = Objects.requireNonNull(dhPrimeChecker);
        this.publicRsaKeyRegister = Objects.requireNonNull(publicRsaKeyRegister);
    }

    public ByteBuf getNonce() {
        return nonce;
    }

    public void setNonce(ByteBuf nonce) {
        this.nonce = Objects.requireNonNull(nonce);
    }

    public ByteBuf getNewNonce() {
        return newNonce;
    }

    public void setNewNonce(ByteBuf newNonce) {
        this.newNonce = Objects.requireNonNull(newNonce);
    }

    public ByteBuf getServerNonce() {
        return serverNonce;
    }

    public void setServerNonce(ByteBuf serverNonce) {
        this.serverNonce = Objects.requireNonNull(serverNonce);
    }

    public ByteBuf getAuthKey() {
        return authKey;
    }

    public void setAuthKey(ByteBuf authKey) {
        this.authKey = Objects.requireNonNull(authKey);
    }

    public long getServerSalt() {
        return serverSalt;
    }

    public void setServerSalt(long serverSalt) {
        this.serverSalt = serverSalt;
    }

    public ByteBuf getAuthKeyHash() {
        return authKeyHash;
    }

    public void setAuthKeyHash(ByteBuf authKeyHash) {
        this.authKeyHash = Objects.requireNonNull(authKeyHash);
    }

    public ServerDHParams getServerDHParams() {
        return serverDHParams;
    }

    public void setServerDHParams(ServerDHParams serverDHParams) {
        this.serverDHParams = Objects.requireNonNull(serverDHParams);
    }

    public AtomicInteger getRetry() {
        return retry;
    }

    public int getServerTimeDiff() {
        return serverTimeDiff;
    }

    public PublicRsaKeyRegister getPublicRsaKeyRegister() {
        return publicRsaKeyRegister;
    }

    public DhPrimeChecker getDhPrimeChecker() {
        return dhPrimeChecker;
    }

    public void setServerTimeDiff(int serverTimeDiff) {
        this.serverTimeDiff = serverTimeDiff;
    }

    public void reset() {
        safeRelease(nonce);
        safeRelease(newNonce);
        safeRelease(serverNonce);
        safeRelease(authKey);
        safeRelease(authKeyHash);

        nonce = null;
        newNonce = null;
        serverNonce = null;
        authKey = null;
        serverSalt = 0;
        serverTimeDiff = 0;
        authKeyHash = null;
        serverDHParams = null;
        retry.set(0);
    }
}
