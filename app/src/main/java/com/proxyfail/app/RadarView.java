package com.proxyfail.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class RadarView extends View {
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float distanceMeters = 100f; // large default (out of range)
    private float maxMeters = 6f; // map RSSI -> up to this many meters
    private float sweepAngle = 0f;

    public RadarView(Context ctx) { this(ctx, null); }
    public RadarView(Context ctx, AttributeSet attrs) { this(ctx, attrs, 0); }
    public RadarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setColor(Color.argb(200, 100, 200, 100));
        circlePaint.setStrokeWidth(2f);

        blipPaint.setStyle(Paint.Style.FILL);
        blipPaint.setColor(Color.argb(220, 255, 80, 80));

        sweepPaint.setStyle(Paint.Style.FILL);
        sweepPaint.setColor(Color.argb(40, 0, 255, 0));
    }

    public void setMaxMeters(float m) {
        this.maxMeters = m;
        invalidate();
    }

    public void setDistanceMeters(double meters) {
        if (Double.isNaN(meters) || meters < 0) distanceMeters = maxMeters + 1f;
        else distanceMeters = (float) meters;
        // rotate sweep a bit to show movement
        sweepAngle += 12f;
        if (sweepAngle >= 360f) sweepAngle -= 360f;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        int cx = w/2;
        int cy = h/2;
        float radius = Math.min(w, h) * 0.45f;

        // draw concentric rings (4)
        for (int i=1;i<=4;i++) {
            float r = radius * i / 4f;
            circlePaint.setAlpha(180 - i*20);
            c.drawCircle(cx, cy, r, circlePaint);
        }

        // draw sweep (a faint arc)
        float sweepRadius = radius;
        c.save();
        c.rotate(sweepAngle, cx, cy);
        c.drawArc(cx - sweepRadius, cy - sweepRadius, cx + sweepRadius, cy + sweepRadius, -4f, 8f, true, sweepPaint);
        c.restore();

        // place blip: map distance to radius (closer => smaller radius)
        float rNorm = Math.min(1f, distanceMeters / maxMeters);
        // invert so 0m => center, maxMeters => outer ring
        float blipR = rNorm * radius;
        // choose an angle for blip (sweepAngle + offset) so it appears moving
        double angleRad = Math.toRadians((sweepAngle + 90) % 360);
        float bx = cx + (float) (Math.cos(angleRad) * blipR);
        float by = cy + (float) (Math.sin(angleRad) * blipR);

        // draw blip
        float blipSize = Math.max(6f, 20f * (1f - rNorm)); // closer => larger
        c.drawCircle(bx, by, blipSize, blipPaint);
    }
}
