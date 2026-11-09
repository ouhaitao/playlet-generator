package org.playlet.comfyui.client;

/** ComfyUI 调用失败。 */
public class ComfyUiException extends RuntimeException {

    public ComfyUiException(String message) {
        super(message);
    }

    public ComfyUiException(String message, Throwable cause) {
        super(message, cause);
    }
}
