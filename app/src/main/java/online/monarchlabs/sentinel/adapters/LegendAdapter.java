package online.monarchlabs.sentinel.adapters;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LegendAdapter extends RecyclerView.Adapter<LegendAdapter.ViewHolder> {

    private final List<String> labels;
    private final List<Integer> colors;

    public LegendAdapter(List<String> labels, List<Integer> colors) {
        this.labels = labels;
        this.colors = colors;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = android.view.LayoutInflater.from(parent.getContext())
                .inflate(online.monarchlabs.sentinel.R.layout.item_chart_legend_premium, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String label = labels.get(position);
        int color = colors.get(position);

        holder.tvLabel.setText(label);

        GradientDrawable circle = (GradientDrawable) holder.colorView.getBackground();
        if (circle == null) {
            circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            holder.colorView.setBackground(circle);
        }
        // Apply tint or color filter if needed, but for shape drawable color is fine
        circle.setColor(color);
    }

    @Override
    public int getItemCount() {
        return labels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View colorView;
        TextView tvLabel;

        ViewHolder(View itemView) {
            super(itemView);
            colorView = itemView.findViewById(online.monarchlabs.sentinel.R.id.vColorIndicator);
            tvLabel = itemView.findViewById(online.monarchlabs.sentinel.R.id.tvLegendLabel);
        }
    }
}
