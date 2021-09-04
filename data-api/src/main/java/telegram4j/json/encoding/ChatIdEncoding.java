package telegram4j.json.encoding;

import org.immutables.encode.Encoding;
import telegram4j.json.api.ChatId;
import telegram4j.json.api.Id;

import java.util.Objects;

@Encoding
public class ChatIdEncoding {
    @Encoding.Impl
    private ChatId value = null;

    @Encoding.Expose
    ChatId get() {
        return value;
    }

    @Encoding.Copy
    public ChatId withLong(long value) {
        return ChatId.of(value);
    }

    @Encoding.Copy
    public ChatId withId(Id value) {
        return ChatId.of(value);
    }

    @Encoding.Copy
    public ChatId withString(String value) {
        return ChatId.of(value);
    }

    @Override
    public String toString() {
        return Objects.toString(value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    public boolean equals(ChatIdEncoding obj) {
        return Objects.equals(value, obj.value);
    }

    @Encoding.Builder
    static class Builder {

        private ChatId id = null;

        @Encoding.Init
        public void setStringValue(String value) {
            this.id = ChatId.of(value);
        }

        @Encoding.Init
        public void setLongValue(long value) {
            this.id = ChatId.of(value);
        }

        @Encoding.Init
        public void setIdValue(Id value) {
            this.id = ChatId.of(value);
        }

        @Encoding.Copy
        public void copyId(ChatId value) {
            this.id = value;
        }

        @Encoding.Build
        ChatId build() {
            return this.id;
        }
    }
}
