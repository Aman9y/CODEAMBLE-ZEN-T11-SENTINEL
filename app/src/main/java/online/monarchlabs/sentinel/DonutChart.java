package online.monarchlabs.sentinel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class DonutChart extends View {

    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private List<Float> values = new ArrayList<>();
    private List<Integer> colors = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    
    private String centerTitle = "";
    private String centerSubtitle = "";

    private OnSliceClickListener listener;
    
    // Geometry
    private float strokeWidth = 40f; // px, calculated dynamic later
    
    public interface OnSliceClickListener {
        void onSliceClick(int index);
    }

    public void setOnSliceClickListener(OnSliceClickListener listener) {
        this.listener = listener;
    }

    public DonutChart(Context context) {
        super(context);
        init();
    }

    public DonutChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        segmentPaint.setStyle(Paint.Style.STROKE);
        
        centerTextPaint.setColor(Color.parseColor("#111827"));
        centerTextPaint.setTextAlign(Paint.Align.CENTER);
        centerTextPaint.setFakeBoldText(true);
        
        subTextPaint.setColor(Color.parseColor("#6B7280"));
        subTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<Float> values, List<Integer> colors, List<String> labels) {
        this.values = new ArrayList<>(values);
        this.colors = new ArrayList<>(colors);
        this.labels = new ArrayList<>(labels);
        invalidate();
    }
    
    public void setCenterText(String title, String subtitle) {
        this.centerTitle = title;
        this.centerSubtitle = subtitle;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Enforce square aspect ratio based on width
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, w); // Square
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float width = getWidth();
        float height = getHeight();
        float minDim = Math.min(width, height);
        float padding = minDim * 0.1f;
        strokeWidth = minDim * 0.12f; // Ring thickness
        
        segmentPaint.setStrokeWidth(strokeWidth);
        
        float radius = (minDim - padding - strokeWidth) / 2f;
        float cx = width / 2f;
        float cy = height / 2f;
        
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        
        if (values.isEmpty()) return;
        
        float total = 0;
        for (float v : values) total += v;
        if (total == 0) total = 1;

        float startAngle = -90f;
        
        for (int i = 0; i < values.size(); i++) {
            float val = values.get(i);
            float sweepAngle = (val / total) * 360f;
            
            // Gap between segments
            float gap = 2f; 
            if (values.size() == 1) gap = 0;
            
            segmentPaint.setColor(colors.get(i % colors.size()));
            
            // Draw arc
            // Fix: if sweep is tiny, don't draw gap
            if (sweepAngle - gap > 0) {
                 canvas.drawArc(bounds, startAngle + gap/2, sweepAngle - gap, false, segmentPaint);
            }
            
            startAngle += sweepAngle;
        }
        
        // Draw Center Text
        centerTextPaint.setTextSize(minDim * 0.15f); // Dynamic size
        subTextPaint.setTextSize(minDim * 0.08f);
        
        canvas.drawText(centerTitle, cx, cy + centerTextPaint.getTextSize() * 0.3f, centerTextPaint);
        // canvas.drawText(centerSubtitle, cx, cy + centerTextPaint.getTextSize() * 0.3f + subTextPaint.getTextSize() * 1.2f, subTextPaint);
    }
}
