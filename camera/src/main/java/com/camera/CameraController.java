package com.camera;

import android.Manifest;
import android.app.Application;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CameraController {
    private static final String TAG = CameraController.class.getSimpleName();
    private static final int SURFACE_PREVIEW = 0;
    private static final int SURFACE_CAPTURE = 1;
    private static final int SURFACE_RECORD = 2;
    private static final int SENSOR_ORIENTATION_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    private Context context = null;
    private CameraManager cameraManager = null;
    private Map<String, Integer> sensorOrientations = new ArrayMap<>();
    private Map<String, String> capturePaths = new ArrayMap<>();
    private Map<String, String> recordPaths = new ArrayMap<>();
    private Map<String, String> thumbnailDirs = new ArrayMap<>();
    private Map<String, Size> captureSizes = new ArrayMap<>();
    private Map<String, Size> recordSizes = new ArrayMap<>();
    private Map<String, Location> captureLocations = new ArrayMap<>();
    private Map<String, Integer> videoEncodingRate = new ArrayMap<>();
    private Map<String, Long> recordTimes = new ArrayMap<>();
    private Map<String, Boolean> isRecordings = new ArrayMap<>();
    private Map<String, Surface[]> surfaces = new ArrayMap<>();
    private Map<String, Surface> previewSurfaces = new ArrayMap<>();
    private Map<String, ImageReader> imageReaders = new ArrayMap<>();
    private Map<String, MediaRecorder> mediaRecorders = new ArrayMap<>();
    private Map<String, CameraDevice> cameraDevices = new ArrayMap<>();
    private Map<String, CameraCaptureSession> cameraCaptureSessions = new ArrayMap<>();
    private Map<String, CaptureRequest.Builder> captureBuilders = new ArrayMap<>();
    private SimpleDateFormat nameDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
    private SimpleDateFormat exifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
    private HandlerThread handlerThread = null;
    private Handler handler = null;
    private ICameraCallback cameraCallback = null;
    private ICaptureCallback captureCallback = null;
    private IRecordCallback recordCallback = null;

    private CameraCaptureSession.CaptureCallback captureSessionCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);

            String cameraId = findCameraId(session);
            if(null == cameraId) {
                Log.e(TAG, "onCaptureStarted: don't find camera id!");
                return;
            }

            String path = capturePaths.get(cameraId);
            Log.i(TAG, "onCaptureStarted: cameraId = " + cameraId + ", path = " + path);

            if (null != captureCallback) {
                captureCallback.onStarted(cameraId, path);
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            String cameraId = findCameraId(session);
            if(null == cameraId) {
                Log.e(TAG, "onCaptureCompleted: don't find camera id!");
                return;
            }

            Log.i(TAG, "onCaptureCompleted: cameraId = " + cameraId + ", path = " + capturePaths.get(cameraId));
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);

            String cameraId = findCameraId(session);
            if(null == cameraId) {
                Log.e(TAG, "onCaptureFailed: don't find camera id!");
                return;
            }

            String path = capturePaths.get(cameraId);
            Log.w(TAG, "onCaptureFailed: cameraId = " + cameraId + ", path = " + path);

            if (null != captureCallback) {
                captureCallback.onFailed(cameraId, path);
            }
        }
    };

    private CameraCaptureSession.CaptureCallback recordSessionCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);

            final String cameraId = findCameraId(session);
            if(null == cameraId) {
                Log.e(TAG, "onCaptureSequenceCompleted: don't find camera id!");
                return;
            }

            final String path = recordPaths.get(cameraId);
            Log.i(TAG, "onCaptureSequenceCompleted: cameraId = " + cameraId + ", path = " + path +
                    ", sequenceId = " + sequenceId + ", frameNumber = " + frameNumber);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "onCaptureSequenceCompleted.SaveRecordThread: +");

                    // for MediaMetadataRetriever
                    releaseRecorder(cameraId);

                    Size size = recordSizes.get(cameraId);
                    ContentValues values = new ContentValues();
                    Bitmap thumbnail = null;
                    Uri uri = null;

                    // insert the video to database
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();

                        mmr.setDataSource(path);
                        values.put(MediaStore.Video.Media.DURATION, mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        thumbnail = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        Log.i(TAG, "onCaptureSequenceCompleted: the video thumbnail(MediaMetadataRetriever) = " + thumbnail);
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }

                    if (null != size) {
                        values.put(MediaStore.Video.Media.WIDTH, size.getWidth());
                        values.put(MediaStore.Video.Media.HEIGHT, size.getHeight());
                    }

                    values.put(MediaStore.Video.Media.DATA, path);
                    values.put(MediaStore.Video.Media.DATE_TAKEN, recordTimes.get(cameraId));
                    uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    Log.i(TAG, "onCaptureSequenceCompleted: insert the video to database, uri = " + uri);

                    // save the video thumbnail and insert it to database
                    if (null != path) {
                        if (null == thumbnail) {
                            thumbnail = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.FULL_SCREEN_KIND);
                            Log.i(TAG, "onCaptureSequenceCompleted: the video thumbnail(ThumbnailUtils) = " + thumbnail);
                        }

                        if (null != thumbnail && null != uri) {
                            try {
                                String thumbnailPath = thumbnailDirs.get(cameraId) + path.substring(path.lastIndexOf(File.separator), path.lastIndexOf('.')) + ".jpg";
                                FileOutputStream fos = new FileOutputStream(thumbnailPath);

                                thumbnail.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                                fos.flush();
                                fos.close();
                                Log.i(TAG, "onCaptureSequenceCompleted: save the video thumbnail, thumbnailPath = " + thumbnailPath);

                                values.clear();
                                values.put(MediaStore.Video.Thumbnails.DATA, thumbnailPath);
                                values.put(MediaStore.Video.Thumbnails.VIDEO_ID, ContentUris.parseId(uri));
                                values.put(MediaStore.Video.Thumbnails.WIDTH, thumbnail.getWidth());
                                values.put(MediaStore.Video.Thumbnails.HEIGHT, thumbnail.getHeight());
                                Uri thumbnailUri = context.getContentResolver().insert(MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI, values);
                                Log.i(TAG, "onCaptureSequenceCompleted: insert the video thumbnail to database, thumbnailUri = " + thumbnailUri);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    if (null != recordCallback) {
                        recordCallback.onCompleted(cameraId, path);
                    }

                    Log.i(TAG, "onCaptureSequenceCompleted.SaveRecordThread: -");
                }
            }, "SaveRecordThread").start();
        }
    };

    private ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            Log.i(TAG, "onImageAvailable: width = " + reader.getWidth() + ", height = " + reader.getHeight());
            // Must call acquireNextImage() and close(),
            // or it will block the thread and the preview surface.
            Image image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            image.close();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "onImageAvailable.SaveImageThread: +");

                    String cameraId = null;

                    // find the camera id
                    for (Map.Entry<String, ImageReader> entry : imageReaders.entrySet()) {
                        if(reader.equals(entry.getValue())){
                            cameraId = entry.getKey();
                            break;
                        }
                    }

                    if(null == cameraId) {
                        return;
                    }

                    String path = capturePaths.get(cameraId);
                    if (null == path) {
                        Log.e(TAG, "onImageAvailable: The capture path is null!");
                        return;
                    }

                    // save the image
                    try {
                        FileOutputStream fos = new FileOutputStream(path);
                        fos.write(bytes);
                        fos.flush();
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Log.i(TAG, "onImageAvailable: save the image, path = " + path);

                    ContentValues values = new ContentValues();
                    Location location = captureLocations.get(cameraId);

                    // extract the image information
                    try {
                        ExifInterface exif = new ExifInterface(path);
                        String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);

                        // save the date time to image
                        if (null != dateTime) {
                            try {
                                Date date = exifDateFormat.parse(dateTime);

                                if (null != date) {
                                    values.put(MediaStore.Images.Media.DATE_TAKEN, date.getTime());
                                }
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }
                        }

                        // save the location to image
                        if (null != location) {
                            float[] latLong = new float[2];

                            if (!exif.getLatLong(latLong)) {
                                double latitude = location.getLatitude();
                                double longitude = location.getLongitude();

                                Log.w(TAG, "onImageAvailable: save the coordinate to image!");
                                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, ConvertUtil.decimalToDMS(latitude));
                                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latitude > 0 ? "N" : "S");
                                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, ConvertUtil.decimalToDMS(longitude));
                                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, longitude > 0 ? "E" : "W");
                                exif.saveAttributes();
                            }

                            if (location.hasAltitude() && -1 == exif.getAltitude(-1)) {
                                double altitude = location.getAltitude();
                                Log.w(TAG, "onImageAvailable: save the altitude to image!");
                                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Double.toString(Math.abs(altitude)) + "/1");
                                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altitude >= 0 ? "0" : "1");
                                exif.saveAttributes();
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // insert the image to database
                    if (null != location) {
                        values.put(MediaStore.Images.Media.LATITUDE, location.getLatitude());
                        values.put(MediaStore.Images.Media.LONGITUDE, location.getLongitude());
                    }

                    values.put(MediaStore.Images.Media.DATA, path);
                    values.put(MediaStore.Images.Media.WIDTH, reader.getWidth());
                    values.put(MediaStore.Images.Media.HEIGHT, reader.getHeight());
                    Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    Log.i(TAG, "onImageAvailable: insert the image to database, uri = " + uri);

                    if (null != captureCallback) {
                        captureCallback.onCompleted(cameraId, path);
                    }

                    Log.i(TAG, "onImageAvailable.SaveImageThread: -");
                }
            }, "SaveImageThread").start();
        }
    };

    private MediaRecorder.OnInfoListener recordInfoListener = new MediaRecorder.OnInfoListener() {
        @Override
        public void onInfo(MediaRecorder mr, int what, int extra) {
            String cameraId = findCameraId(mr);
            if(null == cameraId) {
                return;
            }

            Log.i(TAG, "onInfo: cameraId = " + cameraId + ", path = " + recordPaths.get(cameraId) + ", what = " + what + ", extra = " + extra);
            releaseRecorder(cameraId);
            isRecordings.put(cameraId, false);
        }
    };

    private MediaRecorder.OnErrorListener recordErrorListener = new MediaRecorder.OnErrorListener() {
        @Override
        public void onError(MediaRecorder mr, int what, int extra) {
            String cameraId = findCameraId(mr);
            if(null == cameraId) {
                return;
            }

            String path = recordPaths.get(cameraId);
            Log.e(TAG, "onError: cameraId = " + cameraId + ", path = " + path + ", what = " + what + ", extra = " + extra);
            releaseRecorder(cameraId);
            isRecordings.put(cameraId, false);

            if (null != recordCallback) {
                recordCallback.onError(cameraId, path, what, extra);
            }

        }
    };

    /**
     * The result code returned by API.
     */
    public static class ResultCode {
        /**
         * The constant SUCCESS.
         */
        public static final int SUCCESS = 0;

        /**
         * The constant NO_CAMERA_PERMISSION.
         */
        public static final int NO_CAMERA_PERMISSION = -1;

        /**
         * The constant NO_WRITE_EXTERNAL_STORAGE_PERMISSION.
         */
        public static final int NO_WRITE_EXTERNAL_STORAGE_PERMISSION = -2;

        /**
         * The constant NO_RECORD_AUDIO_PERMISSION.
         */
        public static final int NO_RECORD_AUDIO_PERMISSION = -3;

        /**
         * The constant CAMERA_EXCEPTION.
         */
        public static final int CAMERA_EXCEPTION = -4;

        /**
         * The constant NO_CAMERA_DEVICE.
         */
        public static final int NO_CAMERA_DEVICE = -5;

        /**
         * The constant NO_PREVIEW_SURFACE.
         */
        public static final int NO_PREVIEW_SURFACE = -6;

        /**
         * The constant FAILED_WHILE_RECORDING.
         */
        public static final int FAILED_WHILE_RECORDING = -7;
    }

    /**
     * The interface camera callback.
     */
    public interface ICameraCallback {
        /**
         * The camera state.
         */
        class State {
            /**
             * The constant CAMERA_CLOSED.
             */
            public static final int CAMERA_CLOSED = 0;

            /**
             * The constant CAMERA_OPENED.
             */
            public static final int CAMERA_OPENED = 1;

            /**
             * The constant CAMERA_DISCONNECTED.
             */
            public static final int CAMERA_DISCONNECTED = 2;
        }

        /**
         * The camera error code.
         */
        class ErrorCode {
            /**
             * The constant CAMERA_IN_USE.
             */
            public static final int CAMERA_IN_USE = CameraDevice.StateCallback.ERROR_CAMERA_IN_USE;

            /**
             * The constant MAX_CAMERAS_IN_USE.
             */
            public static final int MAX_CAMERAS_IN_USE = CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;

            /**
             * The constant CAMERA_DISABLED.
             */
            public static final int CAMERA_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED;

            /**
             * The constant CAMERA_DEVICE.
             */
            public static final int CAMERA_DEVICE = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE;

            /**
             * The constant CAMERA_SERVICE.
             */
            public static final int CAMERA_SERVICE = CameraDevice.StateCallback.ERROR_CAMERA_SERVICE;
        }

        /**
         * Called when the camera state changed.
         *
         * @param cameraId the camera id
         * @param state the camera state:
         * <ul>
         * <li>{@link State#CAMERA_CLOSED}
         * <li>{@link State#CAMERA_OPENED}
         * <li>{@link State#CAMERA_DISCONNECTED}
         * <ul/>
         */
        void onState(String cameraId, int state);

        /**
         * Called when the camera error occurred.
         *
         * @param cameraId the camera id
         * @param error the error code:
         * <ul>
         * <li>{@link ErrorCode#CAMERA_IN_USE}
         * <li>{@link ErrorCode#MAX_CAMERAS_IN_USE}
         * <li>{@link ErrorCode#CAMERA_DISABLED}
         * <li>{@link ErrorCode#CAMERA_DEVICE}
         * <li>{@link ErrorCode#CAMERA_SERVICE}
         * <ul/>
         */
        void onError(String cameraId, int error);
    }

    /**
     * The interface capture callback.
     */
    public interface ICaptureCallback {
        /**
         * Called when the capture started.
         *
         * @param cameraId the camera id
         * @param path the captured file full path
         */
        void onStarted(String cameraId, String path);

        /**
         * Called when the capture completed.
         *
         * @param cameraId the camera id
         * @param path the captured file full path
         */
        void onCompleted(String cameraId, String path);

        /**
         * Called when the capture failed.
         *
         * @param cameraId the camera id
         * @param path the captured file full path
         */
        void onFailed(String cameraId, String path);
    }

    /**
     * The interface record callback.
     */
    public interface IRecordCallback {
        /**
         * The error code while recording.
         */
        class ErrorCode {
            /**
             * The constant ERROR_UNKNOWN.
             */
            public static final int UNKNOWN = MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN;

            /**
             * The constant ERROR_SERVER_DIED.
             */
            public static final int SERVER_DIED = MediaRecorder.MEDIA_ERROR_SERVER_DIED;
        }

        /**
         * Called when the record completed.
         *
         * @param cameraId the camera id
         * @param path the record file full path
         */
        void onCompleted(String cameraId, String path);

        /**
         * Called when error occurred while recording.
         *
         * @param cameraId the camera id
         * @param path the record file full path
         * @param what the type of error that has occurred:
         * <ul>
         * <li>{@link ErrorCode#UNKNOWN}
         * <li>{@link ErrorCode#SERVER_DIED}
         * <ul/>
         * @param extra an extra code, specific to the error type
         */
        void onError(String cameraId, String path, int what, int extra);
    }

    public static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ConvertUtil {
        public static String coordinateToDMS(double coordinate) {
            return Location.convert(coordinate, Location.FORMAT_SECONDS);
        }

        public static double DMSToCoordinate(String stringDMS) {
            if (stringDMS == null) return 0;
            String[] split = stringDMS.split(":", 3);
            return Double.parseDouble(split[0]) + Double.parseDouble(split[1]) / 60 + Double.parseDouble(split[2]) / 3600;
        }

        /**
         * 浮点型经纬度值转成度分秒格式
         *
         * @param coord
         * @return
         */
        public static String decimalToDMS(double coord) {
            String output,degrees,minutes,seconds;

            // gets the modulus the coordinate divided by one (MOD1).
            // in other words gets all the numbers after the decimal point.
            // e.g. mod := -79.982195 % 1 == 0.982195
            //
            // next get the integer part of the coord. On other words the whole
            // number part.
            // e.g. intPart := -79

            double mod = coord % 1;
            int intPart = (int) coord;

            // set degrees to the value of intPart
            // e.g. degrees := "-79"

            degrees = String.valueOf(intPart);

            // next times the MOD1 of degrees by 60 so we can find the integer part
            // for minutes.
            // get the MOD1 of the new coord to find the numbers after the decimal
            // point.
            // e.g. coord := 0.982195 * 60 == 58.9317
            // mod := 58.9317 % 1 == 0.9317
            //
            // next get the value of the integer part of the coord.
            // e.g. intPart := 58

            coord = mod * 60;
            mod = coord % 1;
            intPart = (int) coord;
            if (intPart < 0) {
                // Convert number to positive if it's negative.
                intPart *= -1;
            }

            // set minutes to the value of intPart.
            // e.g. minutes = "58"
            minutes = String.valueOf(intPart);

            // do the same again for minutes
            // e.g. coord := 0.9317 * 60 == 55.902
            // e.g. intPart := 55
            coord = mod * 60;
            intPart = (int) coord;
            if (intPart < 0) {
                // Convert number to positive if it's negative.
                intPart *= -1;
            }

            // set seconds to the value of intPart.
            // e.g. seconds = "55"
            seconds = String.valueOf(intPart);

            // I used this format for android but you can change it
            // to return in whatever format you like
            // e.g. output = "-79/1,58/1,56/1"
            output = degrees + "/1," + minutes + "/1," + seconds + "/1";

            // Standard output of D°M′S″
            // output = degrees + "°" + minutes + "'" + seconds + "\"";
            return output;
        }

        public static float convertRationalLatLonToFloat(String rationalString, String ref) {
            try {
                String [] parts = rationalString.split(",");

                String [] pair;
                pair = parts[0].split("/");
                double degrees = Double.parseDouble(pair[0].trim())
                        / Double.parseDouble(pair[1].trim());

                pair = parts[1].split("/");
                double minutes = Double.parseDouble(pair[0].trim())
                        / Double.parseDouble(pair[1].trim());

                pair = parts[2].split("/");
                double seconds = Double.parseDouble(pair[0].trim())
                        / Double.parseDouble(pair[1].trim());

                double result = degrees + (minutes / 60.0) + (seconds / 3600.0);
                if ((ref.equals("S") || ref.equals("W"))) {
                    return (float) -result;
                }
                return (float) result;
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Not valid
                throw new IllegalArgumentException();
            }
        }
    }

    private static class Builder {
        private static CameraController instance = new CameraController();
    }

    public static CameraController getInstance() {
        return Builder.instance;
    }

    private CameraController() {
        try {
            //Application application = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null, (Object[]) null);
            Application application = (Application) Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null, (Object[]) null);

            if (null != application) {
                context = application.getApplicationContext();
            } else {
                throw new NullPointerException();
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);

        File captureDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (null == captureDir || !captureDir.exists()) {
            Log.e(TAG, "CameraController: can't get external pictures directory!");
        }

        File recordDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (null == recordDir || !recordDir.exists()) {
            Log.e(TAG, "CameraController: can't get external movies directory!");
        }

        File thumbnailDir = context.getExternalFilesDir("Thumbnails");
        if (null == thumbnailDir || !thumbnailDir.exists()) {
            Log.e(TAG, "CameraController: can't get external thumbnails directory!");
        }

        String[] cameraIds = getCameraIdList();
        for (String cameraId : cameraIds) {
            try {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientations.put(cameraId, sensorOrientation);
                Log.i(TAG, "CameraController: cameraId = " + cameraId + ", sensor orientation =  " + sensorOrientation);

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (null != map) {
                    Size size = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                    captureSizes.put(cameraId, size);
                    Log.i(TAG, "CameraController: cameraId = " + cameraId + ", capture size = " + size);
                    
                    size = Collections.max(Arrays.asList(map.getOutputSizes(MediaRecorder.class)), new CompareSizesByArea());
                    recordSizes.put(cameraId, size);
                    Log.i(TAG, "CameraController: cameraId = " + cameraId + ", record size = " + size);
                } else {
                    captureSizes.put(cameraId, new Size(1280, 720));
                    recordSizes.put(cameraId, new Size(1280, 720));
                    Log.w(TAG, "CameraController: cameraId = " + cameraId + ", the StreamConfigurationMap is null, use 1280 * 720 as default!");
                }
                
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Log.i(TAG, "CameraController: cameraId = " + cameraId + ", facing =  " + facing);

                Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                Log.i(TAG, "CameraController: cameraId = " + cameraId + ", supported level =  " + level);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            capturePaths.put(cameraId, captureDir.getPath() + File.separator + "dummy.jpg");
            recordPaths.put(cameraId, recordDir.getPath() + File.separator + "dummy.mp4");
            thumbnailDirs.put(cameraId, thumbnailDir.getPath());
            videoEncodingRate.put(cameraId, 3000000);
            isRecordings.put(cameraId, false);
            surfaces.put(cameraId, new Surface[]{null, null, null});
        }
    }

    public String[] getCameraIdList() {
        try {
            return cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return new String[0];
    }

    public Size[] getAvailablePreviewSizes(String cameraId) {
        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (null == map) {
                Log.e(TAG, "getAvailablePreviewSizes: no camera available size!");
                return null;
            }

            return map.getOutputSizes(SurfaceTexture.class);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Size[] getAvailableCaptureSizes(String cameraId) {
        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (null == map) {
                Log.e(TAG, "getAvailableCaptureSizes: no camera available size!");
                return null;
            }

            return map.getOutputSizes(ImageFormat.JPEG);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Size[] getAvailableRecordSizes(String cameraId) {
        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (null == map) {
                Log.e(TAG, "getAvailableRecordSizes: no camera available size!");
                return null;
            }

            return map.getOutputSizes(MediaRecorder.class);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return null;
    }

    public boolean isRecording(String cameraId) {
        Boolean  isRecording = isRecordings.get(cameraId);
        return null != isRecording && isRecording;
    }

    public void setCameraCallback(ICameraCallback callback) {
        cameraCallback = callback;
    }

    public void setPreviewSurface(String cameraId, Surface surface) {
        Log.i(TAG, "setPreviewSurface: cameraId = " + cameraId);
        previewSurfaces.put(cameraId, surface);
    }

    public void setCaptureSize(String cameraId, int width, int height) {
        Log.i(TAG, "setCaptureSize: cameraId = " + cameraId + ", width = " + width + ", height = " + height);

        if (isRecording(cameraId)) {
            Log.e(TAG, "setCaptureSize: failed while recording!");
        }

        ImageReader imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
        imageReader.setOnImageAvailableListener(imageAvailableListener, handler);
        imageReaders.put(cameraId, imageReader);
        captureSizes.put(cameraId, new Size(width, height));
    }

    public boolean setCaptureDir(String cameraId, String dir) {
        Log.i(TAG, "setCaptureDir: cameraId = " + cameraId + ", dir = " + dir);

        if (null == dir) {
            return false;
        }

        File captureDir = new File(dir);

        if (!captureDir.exists()) {
            if (captureDir.mkdirs()) {
                Log.i(TAG, "setCaptureDir: make directory " + dir);
            } else {
                Log.e(TAG, "setCaptureDir: make directory " + dir + " failed!");
                return false;
            }
        }

        capturePaths.put(cameraId, dir + File.separator + "dummy.jpg");

        return true;
    }

    public void setCaptureCallback(ICaptureCallback callback) {
        captureCallback = callback;
    }

    public void setRecordSize(String cameraId, int width, int height) {
        Log.i(TAG, "setRecordSize: cameraId = " + cameraId + ", width = " + width + ", height = " + height);
        recordSizes.put(cameraId, new Size(width, height));
    }

    public boolean setRecordDir(String cameraId, String dir) {
        Log.i(TAG, "setRecordDir: cameraId = " + cameraId + ", dir = " + dir);

        if (null == dir) {
            return false;
        }

        File recordDir = new File(dir);
        if (!recordDir.exists()) {
            if (recordDir.mkdirs()) {
                Log.i(TAG, "setRecordDir: make directory " + dir);
            } else {
                Log.e(TAG, "setRecordDir: make directory " + dir + " failed!");
                return false;
            }
        }
        recordPaths.put(cameraId, dir + File.separator + "dummy.mp4");

        File thumbnailDir = new File(recordDir.getParentFile(), "Thumbnails");
        if (!thumbnailDir.exists()) {
            if(thumbnailDir.mkdirs()) {
                Log.i(TAG, "setRecordDir: make directory " + thumbnailDir.getPath());
            } else {
                Log.e(TAG, "setRecordDir: make directory " + thumbnailDir.getPath() + " failed!");
                return false;
            }
        }
        thumbnailDirs.put(cameraId, thumbnailDir.getPath());

        return true;
    }

    public void setVideoEncodingRate(String cameraId, int bps) {
        Log.i(TAG, "setVideoEncodingRate: cameraId = " + cameraId + ", bps = " + bps);
        videoEncodingRate.put(cameraId, bps);
    }

    public void setRecordCallback(IRecordCallback callback) {
        recordCallback = callback;
    }

    public int open(final String cameraId) {
        Log.i(TAG, "open: cameraId = " + cameraId);

        if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            Log.w(TAG, "open: no camera permission!");
            return ResultCode.NO_CAMERA_PERMISSION;
        }

        if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "open: no record audio permission!");
            return ResultCode.NO_RECORD_AUDIO_PERMISSION;
        }

        if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Log.w(TAG, "open: no write external storage permission!");
            return ResultCode.NO_WRITE_EXTERNAL_STORAGE_PERMISSION;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                Log.w(TAG, "open: no access media location!");
                return ResultCode.NO_WRITE_EXTERNAL_STORAGE_PERMISSION;
            }
        }
        
        try {
            handlerThread = new HandlerThread("CameraHandlerThread");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());

            Size size = captureSizes.get(cameraId);
            ImageReader imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(imageAvailableListener, handler);
            imageReaders.put(cameraId, imageReader);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    String cameraId = camera.getId();

                    Log.i(TAG, "onOpened: cameraId = " + cameraId);
                    cameraDevices.put(cameraId, camera);

                    if (null != cameraCallback) {
                        cameraCallback.onState(cameraId, ICameraCallback.State.CAMERA_OPENED);
                    }
                }

                @Override
                public void onClosed(@NonNull CameraDevice camera) {
                    super.onClosed(camera);
                    Log.i(TAG, "onClosed: cameraId = " + camera.getId());

                    if (null != cameraCallback) {
                        cameraCallback.onState(cameraId, ICameraCallback.State.CAMERA_CLOSED);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    String cameraId = camera.getId();

                    Log.i(TAG, "onDisconnected: cameraId = " + cameraId);

                    if (null != cameraCallback) {
                        cameraCallback.onState(cameraId, ICameraCallback.State.CAMERA_DISCONNECTED);
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    String cameraId = camera.getId();

                    Log.i(TAG, "onError: cameraId = " + cameraId + ", error = " + error);
                    //close(cameraId);

                    if (null != cameraCallback) {
                        cameraCallback.onError(cameraId, error);
                    }
                }
            }, handler);
        } catch (CameraAccessException | IllegalArgumentException e) {
            e.printStackTrace();
            return ResultCode.CAMERA_EXCEPTION;
        }

        return ResultCode.SUCCESS;
    }

    public int close(String cameraId) {
        Log.i(TAG, "close: cameraId = " + cameraId);
        
        // stop record
        stopRecord(cameraId);

        // close session
        CameraCaptureSession cameraCaptureSession = cameraCaptureSessions.get(cameraId);
        if (null != cameraCaptureSession) {
            try {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.abortCaptures();
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
            }

            try {
                cameraCaptureSession.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            cameraCaptureSessions.remove(cameraId);
        }

        // remove surfaces
        CaptureRequest.Builder captureBuilder =  captureBuilders.get(cameraId);
        if (null != captureBuilder) {
            Surface[] ss = surfaces.get(cameraId);

            if (null != ss) {
                for (Surface s : ss) {
                    captureBuilder.removeTarget(ss[SURFACE_CAPTURE]);
                    captureBuilder.removeTarget(ss[SURFACE_PREVIEW]);
                    captureBuilder.removeTarget(ss[SURFACE_RECORD]);
                }
            }

            captureBuilders.remove(cameraId);
        }

        // close device
        CameraDevice cameraDevice = cameraDevices.get(cameraId);
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevices.remove(cameraId);
        }

        // close image reader
        ImageReader imageReader = imageReaders.get(cameraId);
        if (null != imageReader) {
            imageReader.close();
            imageReaders.remove(cameraId);
        }

        // remove callbacks and messages
        if (null != handler) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }

        // terminate thread
        if (null != handlerThread) {
            handlerThread.quitSafely();

            try {
                handlerThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            handlerThread = null;
            handler = null;
        }

        return ResultCode.SUCCESS;
    }

    public int startPreview(String cameraId) {
        Log.i(TAG, "startPreview: cameraId = " + cameraId);

        CameraDevice cameraDevice = cameraDevices.get(cameraId);
        if (null == cameraDevice) {
            return ResultCode.NO_CAMERA_DEVICE;
        }

        Surface previewSurface = previewSurfaces.get(cameraId);
        if (null == previewSurface) {
            return ResultCode.NO_PREVIEW_SURFACE;
        }

        if (isRecording(cameraId)) {
            return ResultCode.FAILED_WHILE_RECORDING;
        }

        boolean isNeedCreateSession = false;
        Surface captureSurface = imageReaders.get(cameraId).getSurface();
        List<Surface> surfaceList= new ArrayList<>();

        if (null == cameraCaptureSessions.get(cameraId)
                || surfaces.get(cameraId)[SURFACE_PREVIEW] != previewSurface
                || surfaces.get(cameraId)[SURFACE_CAPTURE] != captureSurface) {
            isNeedCreateSession = true;
        }

        surfaces.get(cameraId)[SURFACE_PREVIEW] = previewSurface;
        surfaces.get(cameraId)[SURFACE_CAPTURE] = captureSurface;
        surfaceList.add(previewSurface);
        surfaceList.add(captureSurface);

        if (isNeedCreateSession) {
            try {
                cameraDevice.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        String cameraId = session.getDevice().getId();

                        Log.i(TAG, "onConfigured: cameraId = " + cameraId);
                        cameraCaptureSessions.put(cameraId, session);
                        doPreview(cameraId);
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "onConfigureFailed: cameraId = " + session.getDevice().getId());
                    }
                }, handler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                return ResultCode.CAMERA_EXCEPTION;
            }
        } else {
            return doPreview(cameraId);
        }

        return ResultCode.SUCCESS;
    }

    public int stopPreview(String cameraId) {
        Log.i(TAG, "stopPreview: cameraId = " + cameraId);

        if (isRecording(cameraId)) {
            return ResultCode.FAILED_WHILE_RECORDING;
        }

        CameraCaptureSession cameraCaptureSession = cameraCaptureSessions.get(cameraId);

        if (null != cameraCaptureSession) {
            try {
                cameraCaptureSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
            }
        }

        return ResultCode.SUCCESS;
    }

    public int capture(String cameraId) {
        return capture(cameraId, null, null);
    }

    public int capture(String cameraId, String name) {
        return capture(cameraId, name, null);
    }

    public int capture(String cameraId, Location location) {
        return capture(cameraId, null, location);
    }

    public int capture(String cameraId, final String name, final Location location) {
        Log.i(TAG, "capture: cameraId = " + cameraId + ", name = " + name + ", location = " + location);

        CameraDevice cameraDevice = cameraDevices.get(cameraId);
        if (null == cameraDevice) {
            return ResultCode.NO_CAMERA_DEVICE;
        }

        boolean isNeedCreateSession = false;
        Surface previewSurface = previewSurfaces.get(cameraId);
        Surface captureSurface = imageReaders.get(cameraId).getSurface();
        List<Surface> surfaceList= new ArrayList<>();

        if (null == cameraCaptureSessions.get(cameraId)
                || surfaces.get(cameraId)[SURFACE_PREVIEW] != previewSurface
                || surfaces.get(cameraId)[SURFACE_CAPTURE] != captureSurface) {
            isNeedCreateSession = true;
        }

        surfaces.get(cameraId)[SURFACE_PREVIEW] = previewSurface;
        surfaces.get(cameraId)[SURFACE_CAPTURE] = captureSurface;
        surfaceList.add(captureSurface);

        if (null != previewSurface) {
            surfaceList.add(previewSurface);
        }

        if (isNeedCreateSession) {
            try {
                cameraDevice.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        String cameraId = session.getDevice().getId();

                        Log.i(TAG, "onConfigured: cameraId = " + cameraId);
                        cameraCaptureSessions.put(cameraId, session);
                        doCapture(cameraId, name, location);
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "onConfigureFailed: cameraId = " + session.getDevice().getId());
                    }
                }, handler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                return ResultCode.CAMERA_EXCEPTION;
            }
        } else {
            return doCapture(cameraId, name, location);
        }

        return ResultCode.SUCCESS;
    }

    public int startRecord(String cameraId) {
        return startRecord(cameraId, null, 0);
    }

    public int startRecord(String cameraId, String name) {
        return startRecord(cameraId, name, 0);
    }

    public int startRecord(String cameraId, int duration) {
        return startRecord(cameraId, null, duration);
    }

    public int startRecord(String cameraId, String name, int duration) {
        Log.i(TAG, "startRecord: cameraId = " + cameraId + ", name = " + name + ", duration = " + duration);

        final CameraDevice cameraDevice = cameraDevices.get(cameraId);
        if (null == cameraDevice) {
            return ResultCode.NO_CAMERA_DEVICE;
        }

        if (isRecording(cameraId)) {
            return ResultCode.FAILED_WHILE_RECORDING;
        }

        stopPreview(cameraId);
        isRecordings.put(cameraId, true);
        prepareRecorder(cameraId, name, duration);

        final MediaRecorder mediaRecorder = mediaRecorders.get(cameraId);
        final Surface previewSurface = previewSurfaces.get(cameraId);
        Surface captureSurface = imageReaders.get(cameraId).getSurface();
        final Surface recordSurface = mediaRecorder.getSurface();
        List<Surface> surfaceList= new ArrayList<>();

        surfaces.get(cameraId)[SURFACE_PREVIEW] = previewSurface;
        surfaces.get(cameraId)[SURFACE_CAPTURE] = captureSurface;
        surfaces.get(cameraId)[SURFACE_RECORD] = recordSurface;
        surfaceList.add(captureSurface);
        surfaceList.add(recordSurface);

        if (null != previewSurface) {
            surfaceList.add(previewSurface);
        }

        try {
            cameraDevice.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    String cameraId = session.getDevice().getId();

                    Log.i(TAG, "onConfigured: cameraId = " + cameraId);
                    cameraCaptureSessions.put(cameraId, session);

                    try {
                        CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

                        if (null != previewSurface) {
                            captureBuilder.addTarget(previewSurface);
                        }

                        captureBuilder.addTarget(recordSurface);
                        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        captureBuilders.put(cameraId, captureBuilder);
                        session.setRepeatingRequest(captureBuilder.build(), recordSessionCallback, handler);
                        mediaRecorder.start();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "onConfigureFailed: cameraId = " + session.getDevice().getId());
                }
            }, handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return ResultCode.CAMERA_EXCEPTION;
        }

        return ResultCode.SUCCESS;
    }

    public int stopRecord(String cameraId) {
        Log.i(TAG, "stopRecord: cameraId = " + cameraId);

        MediaRecorder mediaRecorder = mediaRecorders.get(cameraId);

        if (null != mediaRecorder) {
            mediaRecorder.stop();
        }

        releaseRecorder(cameraId);
        isRecordings.put(cameraId, false);

        return ResultCode.SUCCESS;
    }

    private int doPreview(String cameraId) {
        try {
            CaptureRequest.Builder captureBuilder =  cameraDevices.get(cameraId).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            captureBuilder.addTarget(surfaces.get(cameraId)[SURFACE_PREVIEW]);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            captureBuilders.put(cameraId, captureBuilder);
            cameraCaptureSessions.get(cameraId).setRepeatingRequest(captureBuilder.build(), null, handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return ResultCode.CAMERA_EXCEPTION;
        }

        return ResultCode.SUCCESS;
    }

    private int doCapture(String cameraId, String name, Location location) {
        try {
            CaptureRequest.Builder captureBuilder = cameraDevices.get(cameraId).createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            WindowManager windowManager = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE));
            String path = capturePaths.get(cameraId).substring(0, capturePaths.get(cameraId).lastIndexOf(File.separator)) +
                    File.separator + (null != name && !name.isEmpty() ? name : nameDateFormat.format(new Date()) + ".jpg");

            if (null != windowManager) {
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(sensorOrientations.get(cameraId), windowManager.getDefaultDisplay().getRotation()));
            }

            capturePaths.put(cameraId, path);
            captureLocations.put(cameraId, location);
            captureBuilder.addTarget(surfaces.get(cameraId)[SURFACE_CAPTURE]);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            captureBuilder.set(CaptureRequest.JPEG_GPS_LOCATION, location);
            captureBuilders.put(cameraId, captureBuilder);
            cameraCaptureSessions.get(cameraId).capture(captureBuilder.build(), captureSessionCallback, handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return ResultCode.CAMERA_EXCEPTION;
        }

        return ResultCode.SUCCESS;
    }

    private void prepareRecorder(String cameraId, String name, int duration) {
        Log.i(TAG, "prepareRecorder: cameraId = " + cameraId + ", name = " + name + ", duration = " + duration);

        MediaRecorder mediaRecorder = new MediaRecorder();
        String path = recordPaths.get(cameraId).substring(0, recordPaths.get(cameraId).lastIndexOf(File.separator)) +
                File.separator + (null != name && !name.isEmpty() ? name : nameDateFormat.format(new Date()) + ".mp4");
        Integer sensorOrientation = sensorOrientations.get(cameraId);
        WindowManager windowManager = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE));

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(path);
        mediaRecorder.setVideoEncodingBitRate(videoEncodingRate.get(cameraId));
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(recordSizes.get(cameraId).getWidth(), recordSizes.get(cameraId).getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setMaxDuration(duration);
        mediaRecorder.setOnInfoListener(recordInfoListener);
        mediaRecorder.setOnErrorListener(recordErrorListener);

        if (null != sensorOrientation && null != windowManager) {
            int rotation = windowManager.getDefaultDisplay().getRotation();

            switch (sensorOrientation) {
                case SENSOR_ORIENTATION_DEGREES:
                    mediaRecorder.setOrientationHint(ORIENTATIONS.get(rotation));
                    break;
                case SENSOR_ORIENTATION_INVERSE_DEGREES:
                    mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                    break;
                default:
                    break;
            }
        }

        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        recordTimes.put(cameraId, System.currentTimeMillis());
        recordPaths.put(cameraId, path);
        mediaRecorders.put(cameraId, mediaRecorder);
    }

    private void releaseRecorder(String cameraId) {
        Log.i(TAG, "releaseRecorder: cameraId = " + cameraId);

        MediaRecorder mediaRecorder = mediaRecorders.get(cameraId);

        if (null != mediaRecorder) {
            synchronized (this) {
                if (null != (mediaRecorder = mediaRecorders.get(cameraId))) {
                    mediaRecorder.reset();
                    mediaRecorder.release();
                    mediaRecorders.remove(cameraId);
                }
            }
        }
    }
    
    private String findCameraId(CameraCaptureSession session) {
        for (Map.Entry<String, CameraCaptureSession> entry : cameraCaptureSessions.entrySet()) {
            if(session.equals(entry.getValue())){
                return entry.getKey();
            }
        }
        
        return null;
    }

    private String findCameraId(MediaRecorder mediaRecorder) {
        for (Map.Entry<String, MediaRecorder> entry : mediaRecorders.entrySet()) {
            if(mediaRecorder.equals(entry.getValue())){
                return entry.getKey();
            }
        }

        return null;
    }

    private int getOrientation(Integer sensorOrientation, int rotation) {
        if (null == sensorOrientation) {
            sensorOrientation = 0;
        }

        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    private int getJpegOrientation(CameraCharacteristics c, int deviceOrientation) {
        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Round device orientation to a multiple of 90
        deviceOrientation = (deviceOrientation + 45) / 90 * 90;

        // Reverse device orientation for front-facing cameras
        boolean facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
        if (facingFront) deviceOrientation = -deviceOrientation;

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        int jpegOrientation = (sensorOrientation + deviceOrientation + 360) % 360;

        return jpegOrientation;
    }
}
