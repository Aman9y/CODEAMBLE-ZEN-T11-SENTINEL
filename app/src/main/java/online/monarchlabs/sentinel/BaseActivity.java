package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import online.monarchlabs.sentinel.utils.InsetsHelper;

public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Enable Edge-to-Edge before any views are initialized
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(layoutResID);
        setupEdgeToEdgeInsets();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        setupEdgeToEdgeInsets();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        setupEdgeToEdgeInsets();
    }

    /**
     * Finds the root content view and applies standard window insets 
     * if the Activity has not opted out.
     */
    private void setupEdgeToEdgeInsets() {
        if (shouldApplyWindowInsets()) {
            View contentFrame = findViewById(android.R.id.content);
            if (contentFrame != null && ((ViewGroup) contentFrame).getChildCount() > 0) {
                View root = ((ViewGroup) contentFrame).getChildAt(0);
                if (shouldApplyImeInsets()) {
                    InsetsHelper.applySystemBarsAndIme(root);
                } else {
                    InsetsHelper.applySystemBars(root);
                }
            }
        }
    }

    /**
     * Override this method to return false for immersive/fullscreen activities 
     * where you want to manually handle edge-to-edge drawing.
     * @return true to automatically apply padding for system bars.
     */
    protected boolean shouldApplyWindowInsets() {
        return true;
    }

    /**
     * Override this method to return true if the activity has heavy text input
     * and needs keyboard (IME) insets applied to the root view.
     * @return true to automatically apply padding for the keyboard.
     */
    protected boolean shouldApplyImeInsets() {
        return false;
    }
}
