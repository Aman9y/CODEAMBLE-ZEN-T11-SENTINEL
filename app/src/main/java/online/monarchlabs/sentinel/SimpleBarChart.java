package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight bar-chart view purposely built for Digital-Wellbeing-style
 * screen-time histograms (7 bars – one per day).
 *
 * Usage:
 *   SimpleBarChart chart = findViewById(R.id.barChart);
 *   chart.setData(valuesInMinutes); // exactly 7 values, Sun-Sat
 */
public class SimpleBarChart extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // Minutes of usage per day (Sun..Sat)
    private List<Float> data = new ArrayList<>();
    private List<String> labels = null;

    // Bar & grid colours – tweak as needed
    private int barColor = Color.parseColor("#4A6CF7"); // Updated to match Primary 600
    private int inactiveColor = 0x44FFFFFF;

    private final List<RectF> barRects = new ArrayList<>();
    private int selectedIndex = -1;
    
    // Performance optimization - cache expensive calculations
    private float lastMax = -1f;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private boolean needsRecalculation = true;

    public interface OnBarClickListener {
        void onBarClick(int index, float valueMinutes);
    }

    private OnBarClickListener clickListener;

    public void setOnBarClickListener(OnBarClickListener listener) {
        this.clickListener = listener;
    }

    public SimpleBarChart(Context context) {
        super(context);
        init();
    }

    public SimpleBarChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SimpleBarChart(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setColor(barColor);
        inactivePaint.setColor(inactiveColor);
    }

    /**
     * Provide a list of day values (minutes). Any number of days is supported.
     */
    public void setData(List<Float> minutesPerDay) {
        setData(minutesPerDay, null);
    }

    public void setData(List<Float> minutesPerDay, List<String> labels) {
        if (minutesPerDay == null || minutesPerDay.isEmpty()) return;
        this.data = new ArrayList<>(minutesPerDay); // Create defensive copy
        this.labels = labels != null ? new ArrayList<>(labels) : null; // Create defensive copy
        // Mark that recalculation is needed
        needsRecalculation = true;
        // Use post() to avoid blocking UI thread
        post(this::invalidate);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data == null || data.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        
        // Check if we need to recalculate based on size or data changes
        if (needsRecalculation || width != lastWidth || height != lastHeight) {
            float max = 0f;
            for (float v : data) max = Math.max(max, v);
            if (max == 0f) max = 1f;
            
            lastMax = max;
            lastWidth = width;
            lastHeight = height;
            needsRecalculation = false;
        }

        float labelArea = 34f; // px reserved at bottom for day labels
        int barsAreaHeight = (int) (height - labelArea);

        int barCount = data.size();
        // Make bars extremely thin and elegant - ULTRA THIN BARS
        float barWidth = width / (barCount * 8.0f); // Ultra thin bars - much thinner than before
        float gap = barWidth * 7.0f; // Large gaps for better visual separation

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int defaultColor = Color.BLACK;
        TypedArray a = getContext().getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try { defaultColor = a.getColor(0, Color.BLACK);} finally { a.recycle(); }
        textPaint.setColor(defaultColor);
        textPaint.setTextSize(22f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float labelTop = height - labelArea;
        float labelCenter = labelTop + labelArea / 2f;
        float textBaseline = labelCenter - (fm.ascent + fm.descent) / 2f;

        barRects.clear();
        float x = gap;
        for (int i = 0; i < barCount; i++) {
            float value = data.get(i);
            float barHeight = (value / lastMax) * barsAreaHeight;
            rect.set(x, barsAreaHeight - barHeight, x + barWidth, barsAreaHeight);

            if (i == selectedIndex) {
                Paint selPaint = new Paint(barPaint);
                selPaint.setColor(Color.parseColor("#1A73E8"));
                canvas.drawRoundRect(rect, 8f, 8f, selPaint);
            } else {
                canvas.drawRoundRect(rect, 8f, 8f, barPaint);
            }

            barRects.add(new RectF(rect));

            // Draw label
            String lbl;
            if (labels != null && labels.size() == barCount) {
                lbl = labels.get(i);
            } else {
                lbl = "D" + (i + 1);
            }
            canvas.drawText(lbl, x + barWidth / 2f, textBaseline, textPaint);

            x += barWidth + gap;
        }

        // Draw guideline rows behind bars (optional)
        float step = barsAreaHeight / 5f;
        for (int i = 1; i <= 4; i++) {
            float y = barsAreaHeight - step * i;
            canvas.drawLine(0, y, width, y, inactivePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            for (int i = 0; i < barRects.size(); i++) {
                if (barRects.get(i).contains(x, y)) {
                    selectedIndex = i;
                    invalidate();
                    if (clickListener != null) {
                        float value = data.get(i);
                        clickListener.onBarClick(i, value);
                    }
                    break;
                }
            }
        }
        return true;
    }
} 