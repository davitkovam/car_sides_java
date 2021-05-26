package org.tensorflow.lite.examples.classification.customview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class Box extends View {
    private Paint paint = new Paint();
    private int previewHeight;
    private int previewWidth;
    public Box(Context context, int height, int width) {
        super(context);
        //they are switched because default orientation is sideways
        this.previewHeight = width;
        this.previewWidth = height;
    }

    @Override
    protected void onDraw(Canvas canvas) { // Override the onDraw() Method
        super.onDraw(canvas);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(10);

        //center
        int canvasHeight = canvas.getHeight();
        int canvasWidth = canvas.getWidth();

        System.out.println("CCheight: " + canvasHeight);
        System.out.println("CCwidth: " + canvasWidth);

        int canvasMin = Math.min(canvasHeight, canvasWidth);
        float scaleFactor = (float)previewHeight/ (float) previewWidth;
        System.out.println("scale factor: " + scaleFactor);
        int borderX0 = 0;
        int borderY0 = 0;
        int borderX1;
        int borderY1;

        //set rectangle to bottom of preview
        if(previewHeight > previewWidth){
            borderX1 = canvasWidth;
            borderY1 = (int) (scaleFactor*(float)canvasWidth);
            //draw guide box
            //canvas.drawRect(borderX0, borderY1-canvasMin, borderX1, borderY1, paint);

            canvas.drawRect(borderX0, borderY0, borderX1, borderX1, paint);
        } else{
            System.out.println("landscape phone layout is not supported!");
            return;
        }



    }
}
