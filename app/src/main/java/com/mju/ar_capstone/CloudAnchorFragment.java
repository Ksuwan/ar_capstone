package com.mju.ar_capstone;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.mju.ar_capstone.helpers.CameraPermissionHelper;
import com.mju.ar_capstone.helpers.CloudAnchorManager;
import com.mju.ar_capstone.helpers.DisplayRotationHelper;
import com.mju.ar_capstone.helpers.FirebaseManager;
import com.mju.ar_capstone.helpers.TapHelper;
import com.mju.ar_capstone.rendering.BackgroundRenderer;
import com.mju.ar_capstone.rendering.ObjectRenderer;
import com.mju.ar_capstone.rendering.PlaneRenderer;
import com.mju.ar_capstone.rendering.PointCloudRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;


public class CloudAnchorFragment extends Fragment implements GLSurfaceView.Renderer {

    private static final String TAG = CloudAnchorFragment.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private Session session;
    private boolean installRequested;

    private FirebaseManager firebaseManager;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final ObjectRenderer virtualObject = new ObjectRenderer();
    private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private final float[] anchorMatrix = new float[16];
    private final float[] andyColor = {139.0f, 195.0f, 74.0f, 255.0f};

    private TapHelper tapHelper;
    private DisplayRotationHelper displayRotationHelper;
    private final CloudAnchorManager cloudAnchorManager = new CloudAnchorManager();

    @Nullable
    private Anchor currentAnchor = null;
    private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

    @Override
    public void onAttach(@NonNull Context context) {

        super.onAttach(context);
        tapHelper = new TapHelper(context);
        firebaseManager = new FirebaseManager();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate from the Layout XML file.
        View rootView = inflater.inflate(R.layout.cloud_anchor_fragment, container, false);
        GLSurfaceView surfaceView = rootView.findViewById(R.id.surfaceView);
        this.surfaceView = surfaceView;
        displayRotationHelper = new DisplayRotationHelper(requireContext());
        surfaceView.setOnTouchListener(tapHelper);

        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);

        // 임시로 넣어놓음(프래그먼트 확인용)
        Button clearButton = rootView.findViewById(R.id.clear_button);
        clearButton.setOnClickListener(v -> onClearButtonPressed());

        return rootView;
    }

    // 임시로 넣어놓음(프래그먼트 확인용)
    private synchronized void onClearButtonPressed() {
        // Clear the anchor from the scene.
        currentAnchor = null;
    }

    //여기 부분이 세션을 만들어줌
    //카메라 퍼미션 확인도 함
    @Override
    public void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
                    CameraPermissionHelper.requestCameraPermission(requireActivity());
                    return;
                }

                // Create the session.
                session = new Session(requireActivity());

                Config config = new Config(session);
                config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
                session.configure(config);


            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            session = null;
            return;
        }

        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(getContext());
            planeRenderer.createOnGlThread(getContext(), "models/trigrid.png");
            pointCloudRenderer.createOnGlThread(getContext());

            virtualObject.createOnGlThread(getContext(), "models/andy.obj", "models/andy.png");
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

            virtualObjectShadow
                    .createOnGlThread(getContext(), "models/andy_shadow.obj", "models/andy_shadow.png");
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow);
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();
            cloudAnchorManager.onUpdate();
            Camera camera = frame.getCamera();

            // Handle one tap per frame.
            handleTap(frame, camera);


            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame);

            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Visualize tracked points.
            // Use try-with-resources to automatically release the point cloud.
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewmtx, projmtx);
            }

            // Visualize planes.
            planeRenderer.drawPlanes(
                    session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

            for(WrappedAnchor wrappedAnchor : wrappedAnchors){
                Anchor anchor = wrappedAnchor.getAnchor();
                Trackable trackable = wrappedAnchor.getTrackable();

                if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
                    anchor.getPose().toMatrix(anchorMatrix, 0);
                    // Update and draw the model and its shadow.
                    virtualObject.updateModelMatrix(anchorMatrix, 1f);
                    virtualObjectShadow.updateModelMatrix(anchorMatrix, 1f);

                    virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor);
                    virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor);
                }
            }
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private void handleTap(Frame frame, Camera camera){
//        if(currentAnchor != null){
//            return; // Do nothing if there was already an anchor.
//        }

        MotionEvent tap = tapHelper.poll();
        if(tap != null && camera.getTrackingState() == TrackingState.TRACKING){
            List<HitResult> hitResultList;
            hitResultList = frame.hitTest(tap);

            for (HitResult hit : hitResultList){
                // Check if any plane was hit, and if it was hit inside the plane polygon
                Trackable trackable = hit.getTrackable();

                // Creates an anchor if a plane or an oriented point was hit.
                if ((trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                || (trackable instanceof Point && ((Point) trackable).getOrientationMode()
                == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)){

                    if(wrappedAnchors.size() >= 20){
                        wrappedAnchors.get(0).getAnchor().detach();
                        wrappedAnchors.remove(0);
                    }

                    wrappedAnchors.add(new WrappedAnchor(hit.createAnchor(), trackable));
                    //여기 아직 정확하지 않음
                    cloudAnchorManager.hostCloudAnchor(session, wrappedAnchors.get(0).getAnchor(), /* ttl= */ 300, this::onHostedAnchorAvailable);

                    break;
                }
            }
        }

    }
    private synchronized void onHostedAnchorAvailable(Anchor anchor) {
        Anchor.CloudAnchorState cloudState = anchor.getCloudAnchorState();
        if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
            String cloudAnchorId = anchor.getCloudAnchorId();
            firebaseManager.setContent(cloudAnchorId);
            Log.d("tttt", "성공");
        }else {
            Log.d("tttt","실패");
        }
    }


}
