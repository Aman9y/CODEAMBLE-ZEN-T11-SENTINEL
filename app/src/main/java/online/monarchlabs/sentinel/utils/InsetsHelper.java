package online.monarchlabs.sentinel.utils;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import online.monarchlabs.sentinel.R;

public class InsetsHelper {

    /**
     * Stores the initial padding of the view so that insets are applied idempotently.
     */
    private static class InitialPadding {
        final int left, top, right, bottom;
        InitialPadding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static InitialPadding getOrCreateInitialPadding(@NonNull View view) {
        // We use an application-specific resource ID defined in ids.xml
        Object tag = view.getTag(R.id.insets_initial_padding);
        
        InitialPadding initialPadding = (InitialPadding) tag;
        if (initialPadding == null) {
            initialPadding = new InitialPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
            view.setTag(R.id.insets_initial_padding, initialPadding);
        }
        return initialPadding;
    }

    /**
     * Applies standard system bar and display cutout insets to the view's padding.
     */
    public static void applySystemBars(@NonNull View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            InitialPadding initialPadding = getOrCreateInitialPadding(v);
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            
            v.setPadding(
                    initialPadding.left + insets.left,
                    initialPadding.top + insets.top,
                    initialPadding.right + insets.right,
                    initialPadding.bottom + insets.bottom
            );
            return windowInsets;
        });
    }

    /**
     * Applies IME (keyboard) insets to the view's padding.
     */
    public static void applyImeInsets(@NonNull View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            InitialPadding initialPadding = getOrCreateInitialPadding(v);
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            
            v.setPadding(
                    initialPadding.left + insets.left,
                    initialPadding.top + insets.top,
                    initialPadding.right + insets.right,
                    initialPadding.bottom + insets.bottom
            );
            return windowInsets;
        });
    }

    /**
     * Applies both system bars and IME insets to the view's padding.
     */
    public static void applySystemBarsAndIme(@NonNull View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            InitialPadding initialPadding = getOrCreateInitialPadding(v);
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            
            v.setPadding(
                    initialPadding.left + insets.left,
                    initialPadding.top + insets.top,
                    initialPadding.right + insets.right,
                    initialPadding.bottom + insets.bottom
            );
            return windowInsets;
        });
    }
}
