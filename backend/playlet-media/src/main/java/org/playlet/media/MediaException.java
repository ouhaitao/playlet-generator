package org.playlet.media;

/** 媒体处理失败。 */
public class MediaException extends RuntimeException {
    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
