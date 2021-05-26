/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.classification;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import net.codejava.networking.MultipartUtility;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.examples.classification.env.BorderedText;
import org.tensorflow.lite.examples.classification.env.Logger;
import org.tensorflow.lite.examples.classification.tflite.Classifier;
import org.tensorflow.lite.examples.classification.tflite.Classifier.Device;
import org.tensorflow.lite.support.image.TensorImage;




public class ClassifierActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();
  private static final Size DESIRED_PREVIEW_SIZE = new Size(256, 256);
  private static final float TEXT_SIZE_DIP = 10;
  private Bitmap rgbFrameBitmap = null;
  private long lastProcessingTimeMs;
  private Integer sensorOrientation;
  private Classifier classifier;
  private BorderedText borderedText;
  /** Input image size of the model along x axis. */
  private int imageSizeX;
  /** Input image size of the model along y axis. */
  private int imageSizeY;

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_ic_camera_connection_fragment;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  public static class NetworkUtil {
    public static final int NOT_CONNECTED = 0;
    public static final int WIFI = 1;
    public static final int MOBILE = 2;
    public static int getConnectionStatus(Context context){
      ConnectivityManager connectivityManager =
              (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

      if(networkInfo != null){
        if(networkInfo.getType() == ConnectivityManager.TYPE_WIFI){
          return WIFI;
        }
        if(networkInfo.getType() == ConnectivityManager.TYPE_MOBILE){
          return MOBILE;
        }
      }
      return NOT_CONNECTED;
    }
  }




  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    recreateClassifier(getDevice(), getNumThreads());
    if (classifier == null) {
      LOGGER.e("No classifier on preview!");
      return;
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
  }

  private boolean isInternetAvailable() {
    ConnectivityManager connectivityManager
            = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
    return activeNetworkInfo != null && activeNetworkInfo.isConnected();
  }

  private void saveImage(Bitmap bitmap, String folder, String realClass) {

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
    String currentTimeStamp = dateFormat.format(new Date());
    //XY_Cars -> X = predicted, Y=real
    Character X = folder.charAt(0);
    Character Y= realClass.charAt(0);
    String filename = X.toString()+Y.toString()+"_" + "Cars"+ currentTimeStamp+".jpg";

    FileOutputStream outputStream = null;
    File file = Environment.getExternalStorageDirectory();
    String filepath = file.getAbsolutePath() + "/CarSides/" + filename;

    File dir = new File(file.getAbsolutePath() + "/CarSides/");
    if (!dir.isDirectory())
    {
      dir.mkdirs();
    }
    LOGGER.i("DIR", dir.toString());

    File outFile = new File(dir,filename);
    try{
      outputStream = new FileOutputStream(outFile);
    }catch (Exception e){
      e.printStackTrace();
    }
    bitmap.compress(Bitmap.CompressFormat.JPEG,100,outputStream);
    try{
      outputStream.flush();
    }catch (Exception e){
      e.printStackTrace();
    }
    try{
      outputStream.close();
    }
    catch (Exception e){
      e.printStackTrace();
    }

    if(isInternetAvailable())
    {
      Boolean uploaded = uploadImage(filepath, filename);
      if(uploaded)
      {
        deleteImage(filepath);
      }
    }

  }
  private void deleteImage(String filepath)
  {
    File file = new File(filepath);
    file.delete();
  }
  @Override
  protected void checkAndUpload(File folder)
  {
    System.out.println("Checking and uploading");
    if(isInternetAvailable()) {
      Log.i("NOVOTO", "Internet Connection Available");
      if (folder == null) {
        File file = Environment.getExternalStorageDirectory();
        folder = new File(file.getAbsolutePath() + "/CarSides");
      }
      System.out.println(folder);
      System.out.println(folder.exists());
      System.out.println(folder.isDirectory());
      File[] filesInFolder = folder.listFiles();
      System.out.println(filesInFolder);
      if(filesInFolder != null) {
        for (File f : filesInFolder) {
          if (!f.isDirectory()) { //check that it's not a dir

            Log.i("Name", f.getName());
            Log.i("Path", f.getPath());
            String path = f.getPath();
            String name = f.getName();
            Thread thread = new Thread(new Runnable() {

              @Override
              public void run() {
                try  {
                  Boolean uploaded = uploadImage(path, name);
                  if (uploaded) {
                    deleteImage(path);
                    System.out.println("Uploaded and Deleted image" + path);
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }
            });

            thread.start();

          } else {
            checkAndUpload(f);
          }
        }
      }
    }
    else
    {
      System.out.println("Internet not available!");
    }


  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return (checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)== PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)== PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)== PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED);
    } else {
      return true;
    }
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d(" Classifier Activity onStart " + this);
    super.onStart();
    if (hasPermission())
    {
      System.out.println("On start checking and uploading");

      runOnUiThread(
              new Runnable (){
                @Override
                public void run() {
                  if(classifier!=null)
                  {
                    checkAndUpload(null);
                  }
                }
              }
      );
     // checkAndUpload(null);
    }


  }
  /*
  @Override
  protected void confusionMatrix()
  {
    String [] classes = {"front", "back", "left", "right", "DIAGONAL"};
    File file = Environment.getExternalStorageDirectory();
    File folder = new File(file.getAbsolutePath() + "/CarSides");
    int [] [] matrix = new int[5][5];
    for(int i=0;i<5;i++)
    {
      for(int j=0;j<5;j++)
      {
        matrix[i][j]=0;
      }
    }
    File[] FoldersInFolder = folder.listFiles();
    if(FoldersInFolder != null) {
      for (File fi : FoldersInFolder) {
        if (fi.isDirectory()) {
          File[] FilesInFolder = fi.listFiles();
          if(FilesInFolder!=null)
          {
            for(File f : FilesInFolder)
            {
              System.out.println("confusion matrix file:"+ f);
              String [] delovi = f.toString().split("/");
              String real = delovi[delovi.length-2];
              String pred = delovi[delovi.length-1].split("Cars")[0];
              int ireal=-1, ipred=-1;
              System.out.println("Real " + real);
              for (int i=0;i<classes.length;i++)
              {
                String c = classes[i];
                if(c.toUpperCase().equals( real.toUpperCase()))
                {
                  ireal = i;
                }
                if(c.toUpperCase().equals( pred.toUpperCase()))
                {
                  ipred = i;
                }
              }
              if(ireal != -1 && ipred != -1) {
                matrix[ireal][ipred] += 1;
              }
            }
          }
        }
      }
    }
    String [] methods = {"Precision", "Recall", "F1-Score"};
    double [][] results=new double[5][3];
    for (int i=0;i<5;i++)
    {
      int sumPred = 0;
      int sumReal = 0;
      for(int j=0;j<5;j++)
      {
        sumPred += matrix[j][i];
        sumReal += matrix[i][j];
      }
      double precision=-1;
      double recall=-1;
      double F1 =-1;
      if(sumPred!=0)
      {
        precision = matrix[i][i]/sumPred;
      }
      if(sumReal!=0)
      {
        recall = matrix[i][i]/sumReal;
      }
      if(precision+recall >=0)
      {
        F1 = (2*precision*recall)/(precision+recall);
      }
      results[i][0]=precision;
      results[i][1]=recall;
      results[i][2]=F1;
    }
    String m="";
    for(int i=0;i<5;i++)
    {
      m+=classes[i].toUpperCase()+": \n";
      for(int j=0;j<3;j++)
      {
        m+=methods[j] + ": "+results[i][j]+"\n";
      }
      m+="\n";
    }
    System.out.println(m);
    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(ClassifierActivity.this);
    alertDialogBuilder.setCancelable(true);
    alertDialogBuilder.setTitle("Confusion Matrix - Accuracy");
    alertDialogBuilder.setMessage(m);
    alertDialogBuilder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();
              }
            })
            .show();

    

  }
   */

  private boolean uploadImage(String filepath, String filename){
    String charset = "UTF-8";
    File uploadFile = new File(filepath);
    //String requestURL = "http://195.14.189.88:55488/upload.php";
    String requestURL = "https://carsides.coci.result.si/upload.php";
    //String requestURL = "http://192.168.30.1:55488/upload.php";


    try {
      MultipartUtility multipart = new MultipartUtility(requestURL, charset);

      multipart.addHeaderField("User-Agent", "mememe");
      multipart.addFormField("name", "BB_"+filename);

      multipart.addFilePart("image", uploadFile);
      List<String> response = multipart.finish();

      LOGGER.i("SERVER REPLIED:");
      boolean successful = false;
      for (String line : response) {
        System.out.println(line);
        if(line.contains("\"error\":\"null\"")){
          successful = true;
        }
      }
      if (successful){
        LOGGER.i("UPLOAD SUCCESSFUL");
        return true;
      } else{
        LOGGER.i("UPLOAD NOT SUCCESSFUL");
        return false;
      }
    } catch (IOException ex) {
      System.err.println(ex);
      LOGGER.i("UPLOAD NOT SUCCESSFUL");
      return false;
    }
  }



  private Bitmap cropPhoto(Bitmap photoBitmap)  {
    // Determine the width/height of the image
    //they are switched because default orientation is sideways
    int height = photoBitmap.getHeight();
    int width = photoBitmap.getWidth();
    System.out.println("photo height: " + height);
    System.out.println("photo width: " + width);

    int photoMin = Math.min(height, width);
    //int x0 = (width-photoMin);
    //int y0 = (height-photoMin);
    int x0 = 0;
    int y0 = 0;

    System.out.println("desired min: " + photoMin);
    System.out.println("x0: " + x0);
    System.out.println("y0: " + y0);

    Matrix matrix = new Matrix();
    matrix.postRotate(sensorOrientation);

    Bitmap cropped = Bitmap.createBitmap(photoBitmap, x0, y0, photoMin, photoMin, matrix, true);
    System.out.println("cropped size: " + cropped.getByteCount());
    System.out.println("cropped height: " + cropped.getHeight());
    System.out.println("cropped width: " + cropped.getWidth());
    photoBitmap.recycle();

    // Scale down to the output size
    int desiredMin = Math.min(imageSizeX, imageSizeY);
    System.out.println("desired min: " + desiredMin);

    Bitmap scaledBitmap = Bitmap.createScaledBitmap(cropped, desiredMin, desiredMin, true);
    //cropped.recycle();

    return scaledBitmap;
    //return cropped;
  }


  @Override
  protected void processImage(int turn) {
    readyForNextImage();

    Button btnSnap = (Button)findViewById(R.id.btnSnap);
    btnSnap.setOnClickListener(new View.OnClickListener(){

      public void onClick(View v){
        try{
          rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
          rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
          final int cropSize = Math.min(previewWidth, previewHeight);

          runInBackground(

                  new Runnable() {

                    @Override
                    public void run() {

                      if (classifier != null) {
                        final long startTime = SystemClock.uptimeMillis();
                        Bitmap croppedBitmap = cropPhoto(rgbFrameBitmap.copy(rgbFrameBitmap.getConfig(), false));


                        final List<Classifier.Recognition> results =
                                classifier.recognizeImage(croppedBitmap, sensorOrientation);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
                        LOGGER.v("Detect: %s", results);
                        if(turn == 2) {

                          //Dialog
                          ImageView image = new ImageView(ClassifierActivity.this);
                          image.setImageBitmap(croppedBitmap);

                          AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(ClassifierActivity.this);
                          alertDialogBuilder.setCancelable(false);
                          alertDialogBuilder.setTitle("Select the side that is on the picture!");
                          String[] items = {"Front", "Back", "Left", "Right", "Diagonal"};
                          alertDialogBuilder.setSingleChoiceItems(items, 0, null)
                                  .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                      dialog.dismiss();
                                      int selectedPosition = ((AlertDialog)dialog).getListView().getCheckedItemPosition();
                                      System.out.println("Popup: "+ items[selectedPosition]);
                                      saveImage(croppedBitmap, results.toArray()[0].toString().split(" ")[1], items[selectedPosition]);
                                    }
                                  }).setView(image)
                                  .show();

                          
                        }

                        //checkAndUpload(null);
                        System.out.println(results.toArray()[0].toString());


                        runOnUiThread(
                                new Runnable() {
                                  @Override
                                  public void run() {
                                    showResultsInBottomSheet(results);
                                    showFrameInfo(previewWidth + "x" + previewHeight);
                                    showCropInfo(imageSizeX + "x" + imageSizeY);
                                    showCameraResolution(cropSize + "x" + cropSize);
                                    showRotationInfo(String.valueOf(sensorOrientation));
                                    showInference(lastProcessingTimeMs + "ms");
                                  }
                                });
                      }
                      readyForNextImage();
                    }
                  });


        } catch (Exception e){
          LOGGER.e("Something isn't snappy, lad.", e);
        }
      }
    });

  }

  @Override
  protected void onInferenceConfigurationChanged() {
    if (rgbFrameBitmap == null) {
      // Defer creation until we're getting camera frames.
      return;
    }
    final Device device = getDevice();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateClassifier(device, numThreads));
  }

  private void recreateClassifier(Device device, int numThreads) {
    if (classifier != null) {
      LOGGER.d("Closing classifier.");
      classifier.close();
      classifier = null;
    }
    try {
      LOGGER.d(
          "Creating classifier (device=%s, numThreads=%d)", device, numThreads);
      classifier = Classifier.create(this, device, numThreads);
    } catch (IOException e) {
      LOGGER.e(e, "Failed to create classifier.");
    }

    // Updates the input image size.
    imageSizeX = classifier.getImageSizeX();
    imageSizeY = classifier.getImageSizeY();
  }
}
