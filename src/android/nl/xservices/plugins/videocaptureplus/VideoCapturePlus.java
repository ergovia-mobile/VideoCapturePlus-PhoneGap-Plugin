package nl.xservices.plugins.videocaptureplus;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class VideoCapturePlus extends CordovaPlugin {

  private static final String VIDEO_3GPP = "video/3gpp";
  private static final String VIDEO_MP4 = "video/mp4";

  private static final int CAPTURE_VIDEO = 2;     // Constant for capture video
  private static final String LOG_TAG = "VideoCapturePlus";
  private static final int CAPTURE_NO_MEDIA_FILES = 3;

  private CallbackContext callbackContext;        // The callback context from which we were invoked.
  private long limit;                             // the number of pics/vids/clips to take
  private int duration;                           // optional max duration of video recording in seconds
  private boolean highquality;                    // optional setting for controlling the video quality
  private boolean frontcamera;                    // optional setting for starting video capture with the frontcamera
  private String fileName;                        // optional string for video file name
  private JSONArray results;                      // The array of results to be returned to the user

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    this.callbackContext = callbackContext;
    this.limit = 1;
    this.duration = 0;
    this.highquality = false;
    this.frontcamera = false;
    this.results = new JSONArray();

    JSONObject options = args.optJSONObject(0);
    if (options != null) {
      limit = options.optLong("limit", 1);
      duration = options.optInt("duration", 0);
      highquality = options.optBoolean("highquality", false);
      frontcamera = options.optBoolean("frontcamera", false);
      fileName = options.optString("fileName", "VideoCapturePlus");
    }

    if (action.equals("getFormatData")) {
      JSONObject obj = getFormatData(args.getString(0), args.getString(1));
      callbackContext.success(obj);
      return true;
    } else if (action.equals("captureVideo")) {
      this.captureVideo(duration, highquality, frontcamera);
    } else {
      return false;
    }
    return true;
  }

  /**
   * Provides the media data file data depending on it's mime type
   *
   * @param filePath path to the file
   * @param mimeType of the file
   * @return a MediaFileData object
   */
  private JSONObject getFormatData(String filePath, String mimeType) throws JSONException {
    Uri fileUrl = filePath.startsWith("file:") ? Uri.parse(filePath) : Uri.fromFile(new File(filePath));
    JSONObject obj = new JSONObject();
    // setup defaults
    obj.put("height", 0);
    obj.put("width", 0);
    obj.put("bitrate", 0);
    obj.put("duration", 0);
    obj.put("codecs", "");

    // If the mimeType isn't set the rest will fail, so let's see if we can determine it.
    if (mimeType == null || "".equals(mimeType) || "null".equals(mimeType)) {
      mimeType = FileHelper.getMimeType(fileUrl, cordova);
    }
    Log.d(LOG_TAG, "Mime type = " + mimeType);

    if (mimeType.equals(VIDEO_3GPP) || mimeType.equals(VIDEO_MP4)) {
      obj = getAudioVideoData(filePath, obj);
    }
    return obj;
  }

  private JSONObject getAudioVideoData(String filePath, JSONObject obj) throws JSONException {
    MediaPlayer player = new MediaPlayer();
    try {
      player.setDataSource(filePath);
      player.prepare();
      obj.put("duration", player.getDuration() / 1000);
      obj.put("height", player.getVideoHeight());
      obj.put("width", player.getVideoWidth());
    } catch (IOException e) {
      Log.d(LOG_TAG, "Error: loading video file");
    }
    return obj;
  }

  /**
   * Sets up an intent to capture video.  Result handled by onActivityResult()
   */
  private void captureVideo(int duration, boolean highquality, boolean frontcamera) {
    Intent intent = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE);

    if (highquality) {
      intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
    } else {
      // If high quality set to false, force low quality for devices that default to high quality
      intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
    }

    if (frontcamera) {
      intent.putExtra("android.intent.extras.CAMERA_FACING", android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    // consider adding an allowflash param, setting Camera.Parameters.FLASH_MODE_ON/OFF/AUTO

    if (Build.VERSION.SDK_INT > 7) {
      intent.putExtra("android.intent.extra.durationLimit", duration);
    }

    this.cordova.startActivityForResult(this, intent, CAPTURE_VIDEO);
  }

  /**
   * Called when the video view exits.
   *
   * @param requestCode The request code originally supplied to startActivityForResult(),
   *                    allowing you to identify who this result came from.
   * @param resultCode  The integer result code returned by the child activity through its setResult().
   * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
   */
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (resultCode == Activity.RESULT_OK) {
      if (requestCode == CAPTURE_VIDEO) {
        Uri data = null;
        if (intent != null) {
          // Get the uri of the video clip
          data = intent.getData();
        }

        // create a file object from the uri
        if (data == null) {
          this.fail(createErrorObject(CAPTURE_NO_MEDIA_FILES, "Error: data is null"));
        } else {
          results.put(createMediaFile(data));
          if (results.length() >= limit) {
            // Send Uri back to JavaScript for viewing video
            this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, results));
          } else {
            // still need to capture more video clips
            captureVideo(duration, highquality, frontcamera);
          }
        }
      }
    } else if (resultCode == Activity.RESULT_CANCELED) {
      // If we have partial results send them back to the user
      if (results.length() > 0) {
        this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, results));
      } else {
        this.fail(createErrorObject(CAPTURE_NO_MEDIA_FILES, "Canceled."));
      }
    } else {
      // If we have partial results send them back to the user
      if (results.length() > 0) {
        this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, results));
      } else {
        this.fail(createErrorObject(CAPTURE_NO_MEDIA_FILES, "Did not complete!"));
      }
    }
  }

  private JSONObject createMediaFile(final Uri data) {
    Future<JSONObject> result = cordova.getThreadPool().submit(new Callable<JSONObject>() {
      @Override
      public JSONObject call() throws Exception {
        File fp = webView.getResourceApi().mapUriToFile(data);
        JSONObject obj = new JSONObject();
        try {
          // File properties
          obj.put("name", fp.getName());
          obj.put("fullPath", fp.toURI().toString());
          // Because of an issue with MimeTypeMap.getMimeTypeFromExtension() all .3gpp files
          // are reported as video/3gpp. I'm doing this hacky check of the URI to see if it
          // is stored in the audio or video content store.
          if (fp.getAbsoluteFile().toString().endsWith(".3gp") || fp.getAbsoluteFile().toString().endsWith(".3gpp")) {
            obj.put("type", VIDEO_3GPP);
          } else {
            obj.put("type", FileHelper.getMimeType(Uri.fromFile(fp), cordova));
          }
          obj.put("lastModifiedDate", fp.lastModified());
          obj.put("size", fp.length());
        } catch (JSONException e) {
          // this will never happen
          e.printStackTrace();
        }
        return obj;
      }
    });
    try {
      return result.get();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      e.printStackTrace();
    }
    return null;
  }

  private JSONObject createErrorObject(int code, String message) {
    JSONObject obj = new JSONObject();
    try {
      obj.put("code", code);
      obj.put("message", message);
    } catch (JSONException ignore) {
      // This will never happen
    }
    return obj;
  }

  public void fail(JSONObject err) {
    this.callbackContext.error(err);
  }
}
