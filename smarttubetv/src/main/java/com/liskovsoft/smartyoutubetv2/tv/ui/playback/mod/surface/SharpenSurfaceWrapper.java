package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A video surface that sharpens the picture.<br/>
 * <br/>
 * The decoder draws into a texture, and the texture is drawn to the screen through a small filter:
 * every pixel gets more of itself and less of its four neighbours (the same 3x3 kernel as in the
 * player of Extreme InfiniTV, applied after the picture is scaled to the screen).
 * It crisps the edges; it cannot add detail that was never in the stream.<br/>
 * <br/>
 * NOTE: costs GPU time for every frame and goes around the HDR path of the device.
 * That's why the regular surface is used whenever the sharpening is off.
 */
public class SharpenSurfaceWrapper implements SurfaceWrapper {
    private static final String TAG = SharpenSurfaceWrapper.class.getSimpleName();
    /**
     * How much of each neighbouring pixel is taken away
     */
    public static final float STRENGTH_LIGHT = 0.22f;
    public static final float STRENGTH_STRONG = 0.5f;
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec2 uStepX;\n" +
            "uniform vec2 uStepY;\n" +
            "varying vec2 vCenter;\n" +
            "varying vec2 vLeft;\n" +
            "varying vec2 vRight;\n" +
            "varying vec2 vUp;\n" +
            "varying vec2 vDown;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vCenter = (uTexMatrix * aTexCoord).xy;\n" +
            "  vLeft = vCenter - uStepX;\n" +
            "  vRight = vCenter + uStepX;\n" +
            "  vUp = vCenter - uStepY;\n" +
            "  vDown = vCenter + uStepY;\n" +
            "}\n";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uStrength;\n" +
            "varying vec2 vCenter;\n" +
            "varying vec2 vLeft;\n" +
            "varying vec2 vRight;\n" +
            "varying vec2 vUp;\n" +
            "varying vec2 vDown;\n" +
            "void main() {\n" +
            "  vec3 center = texture2D(uTexture, vCenter).rgb;\n" +
            "  vec3 around = texture2D(uTexture, vLeft).rgb + texture2D(uTexture, vRight).rgb +\n" +
            "      texture2D(uTexture, vUp).rgb + texture2D(uTexture, vDown).rgb;\n" +
            // The weights sum to one: the brightness stays the same
            "  gl_FragColor = vec4(clamp(center * (1.0 + 4.0 * uStrength) - around * uStrength, 0.0, 1.0), 1.0);\n" +
            "}\n";
    private static volatile float sStrength = STRENGTH_LIGHT;
    private final GLSurfaceView mVideoSurface;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SurfaceHolder.Callback mMediaPlaybackCallback;
    private Surface mAnnouncedSurface;
    private int mState = SURFACE_NOT_CREATED;

    /**
     * Applied to the next frame
     */
    public static void setStrength(float strength) {
        sStrength = strength;
    }

    public SharpenSurfaceWrapper(Context context) {
        mVideoSurface = new GLSurfaceView(context);
        mVideoSurface.setId(R.id.video_surface);
        mVideoSurface.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        mVideoSurface.setEGLContextClientVersion(2);
        mVideoSurface.setPreserveEGLContextOnPause(true);
        mVideoSurface.setRenderer(new VideoRenderer());
        mVideoSurface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mVideoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // The texture is announced by the renderer when it is ready
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // NOP
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                announce(null);
            }
        });
    }

    @Override
    public void setSurfaceHolderCallback(SurfaceHolder.Callback callback) {
        mMediaPlaybackCallback = callback;

        if (callback != null && mState == SURFACE_CREATED) {
            callback.surfaceCreated(new TextureViewSurfaceHolder(mAnnouncedSurface));
        }
    }

    @Override
    public View getSurfaceView() {
        return mVideoSurface;
    }

    /**
     * Tells the player where to draw (null: nowhere). Main thread.
     */
    private void announce(Surface surface) {
        if (mAnnouncedSurface == surface) {
            return;
        }

        if (mAnnouncedSurface != null && mMediaPlaybackCallback != null) {
            mMediaPlaybackCallback.surfaceDestroyed(new TextureViewSurfaceHolder(mAnnouncedSurface));
        }

        mAnnouncedSurface = surface;
        mState = surface != null ? SURFACE_CREATED : SURFACE_NOT_CREATED;

        if (surface != null && mMediaPlaybackCallback != null) {
            mMediaPlaybackCallback.surfaceCreated(new TextureViewSurfaceHolder(surface));
        }
    }

    private final class VideoRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final FloatBuffer mVertices = toBuffer(new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
        private final FloatBuffer mTexCoords = toBuffer(new float[] {0, 0, 1, 0, 0, 1, 1, 1});
        private final float[] mTexMatrix = new float[16];
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private int mProgram;
        private int mTextureId;
        private int mWidth = 1;
        private int mHeight = 1;

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            // NOTE: also called when the context of the GPU is lost: everything has to be made again
            Surface oldSurface = mSurface;
            SurfaceTexture oldTexture = mSurfaceTexture;

            mProgram = createProgram();

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setOnFrameAvailableListener(this);
            mSurface = new Surface(mSurfaceTexture);

            if (oldSurface != null) {
                mMainHandler.post(() -> {
                    announce(null);
                    oldSurface.release();
                    oldTexture.release();
                });
            }
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            mWidth = Math.max(width, 1);
            mHeight = Math.max(height, 1);
            GLES20.glViewport(0, 0, width, height);

            Surface surface = mSurface;
            mMainHandler.post(() -> announce(surface));
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            if (mSurfaceTexture == null || mProgram == 0) {
                return;
            }

            try {
                mSurfaceTexture.updateTexImage();
            } catch (RuntimeException e) {
                Log.e(TAG, "Can't take the frame: %s", e.getMessage());
                return;
            }

            mSurfaceTexture.getTransformMatrix(mTexMatrix);

            GLES20.glUseProgram(mProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "uTexture"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mProgram, "uTexMatrix"), 1, false, mTexMatrix, 0);
            // One pixel of the screen in the coordinates of the texture (the matrix may flip and crop)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(mProgram, "uStepX"), mTexMatrix[0] / mWidth, mTexMatrix[1] / mWidth);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(mProgram, "uStepY"), mTexMatrix[4] / mHeight, mTexMatrix[5] / mHeight);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(mProgram, "uStrength"), sStrength);

            int position = GLES20.glGetAttribLocation(mProgram, "aPosition");
            int texCoord = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, mVertices);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, mTexCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mVideoSurface.requestRender();
        }

        private int createProgram() {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

            if (vertex == 0 || fragment == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);

            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);

            if (status[0] == 0) {
                Log.e(TAG, "Can't link the shaders: %s", GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return 0;
            }

            return program;
        }

        private int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);

            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);

            if (status[0] == 0) {
                Log.e(TAG, "Can't compile a shader: %s", GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }

            return shader;
        }
    }

    private static FloatBuffer toBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }
}
