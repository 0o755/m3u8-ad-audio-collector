/* 管理 TextureView Surface 所有权，等待 Probe 清除完成后才释放宿主对象。 */
package com.fongmi.ad.collector.ui;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

final class VideoSurfaceController implements TextureView.SurfaceTextureListener {
    interface Listener {
        void onAttach(Surface surface);
        void onClear(Surface surface, Runnable onCleared);
    }

    private final TextureView view;
    private final Listener listener;
    private Surface surface;
    private boolean detaching;
    private boolean closed;
    private Runnable closeCompletion;

    VideoSurfaceController(TextureView view, Listener listener) {
        this.view = view;
        this.listener = listener;
        view.setSurfaceTextureListener(this);
        if (view.isAvailable()) onSurfaceTextureAvailable(view.getSurfaceTexture(),
                view.getWidth(), view.getHeight());
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,
                                                     int width, int height) {
        if (closed || texture == null) return;
        surface = new Surface(texture);
        listener.onAttach(surface);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,
                                                       int width, int height) {
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        Surface current = surface;
        surface = null;
        if (current == null) return true;
        detaching = true;
        listener.onClear(current, () -> {
            current.release();
            texture.release();
            finishDetach();
        });
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    void close(Runnable completion) {
        if (closed) return;
        closed = true;
        closeCompletion = completion;
        view.setSurfaceTextureListener(null);
        Surface current = surface;
        surface = null;
        if (current == null) {
            if (!detaching) finishClose();
            return;
        }
        detaching = true;
        listener.onClear(current, () -> {
            current.release();
            finishDetach();
        });
    }

    private void finishDetach() {
        detaching = false;
        if (closed) finishClose();
    }

    private void finishClose() {
        Runnable completion = closeCompletion;
        closeCompletion = null;
        if (completion != null) completion.run();
    }
}
