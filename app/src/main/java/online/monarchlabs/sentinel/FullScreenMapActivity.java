package online.monarchlabs.sentinel;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import online.monarchlabs.sentinel.utils.ChildDisplayName;

/**
 * Full screen map view — shows the child's live location.
 * Opened when parent taps the map card on the dashboard.
 * Receives "childDeviceId" and "childName" from the launching Intent.
 * Blue button (bottom-right) pans & zooms to child location.
 * Minimize button (top-left) returns to the dashboard.
 */
public class FullScreenMapActivity extends BaseActivity implements OnMapReadyCallback {

    @Override
    protected boolean shouldApplyWindowInsets() {
        return false;
    }

    private static final String MAP_VIEW_BUNDLE_KEY = "MapViewBundleKey";

    private MapView mapView;
    private GoogleMap googleMap;

    // Firebase location listener
    private DatabaseReference locationRef;
    private ValueEventListener locationListener;

    // Child location state
    private Marker childMarker;
    private LatLng lastChildLocation;
    private boolean firstLocationReceived = false;
    private boolean waitingForFreshLocation = false;
    private long locationRequestStartTime = 0L;

    // Child info from intent
    private String childDeviceId;
    private String childName;

    // Status Badge Views
    private View fullMapStatusBadge;
    private View fullMapStatusDot;
    private View fullMapStatusProgress;
    private TextView tvFullMapStatusText;
    private android.os.Handler locationTimeoutHandler;
    private Runnable locationTimeoutRunnable;
    private long lastLocationTimestamp = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_map);

        // Read child identity from the launching Intent
        childDeviceId = getIntent().getStringExtra("childDeviceId");
        childName = getIntent().getStringExtra("childName");
        if (childName == null || childName.isEmpty()) childName = "Child";

        double lastLat = getIntent().getDoubleExtra("lastLat", 999.0);
        double lastLng = getIntent().getDoubleExtra("lastLng", 999.0);
        if (lastLat != 999.0 && lastLng != 999.0) {
            lastChildLocation = new LatLng(lastLat, lastLng);
        }
        lastLocationTimestamp = getIntent().getLongExtra("lastTimestamp", 0L);

        // Query database in background to fetch actual child userName if available
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            String parentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null
                    ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
            if (parentUserId != null) {
                FirebaseDatabase.getInstance().getReference("v2")
                        .child("parent_device_links")
                        .child(parentUserId)
                        .child(childDeviceId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot snapshot) {
                                if (snapshot.exists()) {
                                    String userName = snapshot.child("userName").getValue(String.class);
                                    if (userName == null || userName.isEmpty()) {
                                        userName = snapshot.child("deviceName").getValue(String.class);
                                    }
                                    String displayName = ChildDisplayName.resolve(
                                            childDeviceId, userName);
                                    if (!ChildDisplayName.FALLBACK.equals(displayName)) {
                                        childName = displayName;
                                        if (childMarker != null) {
                                            childMarker.setTitle(childName);
                                            childMarker.setIcon(BitmapDescriptorFactory.fromBitmap(createInitialsBitmap(childName)));
                                        }
                                    }
                                }
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {}
                        });
            }
        }

        // Set up MapView
        mapView = findViewById(R.id.fullScreenMapView);
        fullMapStatusBadge = findViewById(R.id.fullMapStatusBadge);
        fullMapStatusDot = findViewById(R.id.fullMapStatusDot);
        fullMapStatusProgress = findViewById(R.id.fullMapStatusProgress);
        tvFullMapStatusText = findViewById(R.id.tvFullMapStatusText);
        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle(MAP_VIEW_BUNDLE_KEY);
        }
        mapView.onCreate(mapViewBundle);
        mapView.getMapAsync(this);

        // Minimize button — go back to dashboard
        FrameLayout btnMinimize = findViewById(R.id.btnMinimizeMap);
        btnMinimize.setOnClickListener(v -> finish());

        // Blue location button — fly to child
        FrameLayout btnMyLocation = findViewById(R.id.btnMyLocationFull);
        btnMyLocation.setOnClickListener(v -> goToChildLocation());
    }

    /** Pan & zoom to child AND request a fresh GPS fix from the child device. */
    private void goToChildLocation() {
        if (waitingForFreshLocation) {
            // Already actively locating - just animate camera to last known position and skip sending duplicate request
            if (lastChildLocation != null && googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lastChildLocation, 15f));
            }
            return;
        }

        showLoadingOverlay();
        // Animate camera to last known position immediately (if available)
        if (lastChildLocation != null && googleMap != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lastChildLocation, 15f));
        }

        // Signal the child device to get a fresh GPS fix right now
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            waitingForFreshLocation = true;
            locationRequestStartTime = System.currentTimeMillis();
            online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                    .requestLocationRefresh(childDeviceId);
        } else {
            hideLoadingOverlay();
            Toast.makeText(this, "No child device connected", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMapReady(GoogleMap map) {
        googleMap = map;
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        // If we already have lastChildLocation from intent, show it immediately on map load
        if (lastChildLocation != null) {
            boolean initiallyOffline = getIntent().getBooleanExtra("isOffline", false);
            updateChildMarker(initiallyOffline);
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(lastChildLocation, 15f));
            firstLocationReceived = true;

            if (initiallyOffline) {
                String offlineMsg = "GPS is off on child device";
                showGpsOffWarning(true, offlineMsg);
            }
            updateLastSeenUI();
        } else {
            // Default world view until the first child location arrives
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(20.0, 0.0), 2f));
        }

        // Begin listening for child location from Firebase
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            startLocationListener();
        }
    }

    private void startLocationListener() {
        startLocationListener(true);
    }

    private void startLocationListener(boolean triggerFreshRequest) {
        if (locationListener != null && locationRef != null) {
            try { locationRef.removeEventListener(locationListener); } catch (Exception ignored) {}
            locationRef.keepSynced(false);
        }
        locationRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("locations")
                .child(childDeviceId);

        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                Long timestamp = snapshot.child("timestamp").getValue(Long.class);
                if (timestamp != null) {
                    lastLocationTimestamp = timestamp;
                }
                // Check GPS-off flag
                Boolean gpsOff = snapshot.child("gps_off").getValue(Boolean.class);
                String status = snapshot.child("status").getValue(String.class);

                boolean isOffline = Boolean.TRUE.equals(gpsOff) || "permission_denied".equals(status);
                String offlineMsg = null;
                if ("permission_denied".equals(status)) {
                    offlineMsg = "Location permission denied";
                } else if (Boolean.TRUE.equals(gpsOff)) {
                    offlineMsg = "GPS is off on child device";
                }

                showGpsOffWarning(isOffline, offlineMsg);
                if (isOffline) {
                    hideLoadingOverlay();
                    waitingForFreshLocation = false;
                }

                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);
                if (lat != null && lng != null) {
                    lastChildLocation = new LatLng(lat, lng);
                    updateChildMarker(isOffline);
                } else {
                    if (lastChildLocation != null) {
                        updateChildMarker(isOffline);
                    } else {
                        if (childMarker != null) {
                            childMarker.remove();
                            childMarker = null;
                        }
                    }
                }

                if (!isOffline && waitingForFreshLocation && timestamp != null && timestamp >= locationRequestStartTime) {
                    hideLoadingOverlay();
                    waitingForFreshLocation = false;
                } else {
                    updateLastSeenUI();
                }

                // Auto-fly to child on first location received
                if (lastChildLocation != null && !firstLocationReceived) {
                    firstLocationReceived = true;
                    googleMap.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(lastChildLocation, 15f));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        locationRef.addValueEventListener(locationListener);

        if (triggerFreshRequest) {
            // Auto-request a fresh fix as soon as the full-screen map opens
            waitingForFreshLocation = true;
            locationRequestStartTime = System.currentTimeMillis();
            showLoadingOverlay();
            online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                    .requestLocationRefresh(childDeviceId);
        }
    }

    private void showGpsOffWarning(boolean show, String message) {
        android.widget.TextView tv = findViewById(R.id.tvFullMapGpsOff);
        if (tv != null) {
            if (show && message != null) {
                tv.setText("⚠️ " + message);
            }
            tv.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void showLoadingOverlay() {
        if (fullMapStatusProgress != null) fullMapStatusProgress.setVisibility(View.VISIBLE);
        if (fullMapStatusDot != null) fullMapStatusDot.setVisibility(View.GONE);
        if (tvFullMapStatusText != null) tvFullMapStatusText.setText("Locating...");
        if (fullMapStatusBadge != null) fullMapStatusBadge.setVisibility(View.VISIBLE);

        // Cancel previous timeout runnable if active
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }

        // Start a 15-second locating timeout
        locationTimeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        locationTimeoutRunnable = () -> {
            waitingForFreshLocation = false;
            hideLoadingOverlay();
        };
        locationTimeoutHandler.postDelayed(locationTimeoutRunnable, 15000);
    }

    private void hideLoadingOverlay() {
        if (fullMapStatusProgress != null) fullMapStatusProgress.setVisibility(View.GONE);
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }
        updateLastSeenUI();
    }

    private void updateLastSeenUI() {
        if (fullMapStatusBadge != null && tvFullMapStatusText != null && fullMapStatusDot != null) {
            if (lastLocationTimestamp > 0) {
                String statusStr = formatLastSeenTime(lastLocationTimestamp);
                tvFullMapStatusText.setText(statusStr);
                fullMapStatusDot.setVisibility(View.VISIBLE);
                if ("Live".equals(statusStr)) {
                    fullMapStatusDot.setBackgroundResource(R.drawable.bg_circle_green);
                } else {
                    fullMapStatusDot.setBackgroundResource(R.drawable.bg_circle_neutral);
                }
                fullMapStatusBadge.setVisibility(View.VISIBLE);
            } else {
                fullMapStatusBadge.setVisibility(View.GONE);
            }
        }
    }

    private String formatLastSeenTime(long timestamp) {
        long diffMs = System.currentTimeMillis() - timestamp;
        if (diffMs < 0) diffMs = 0;
        
        long diffSec = diffMs / 1000;
        if (diffSec < 60) {
            return "Live";
        }
        
        long diffMin = diffSec / 60;
        if (diffMin < 60) {
            return "Last seen " + diffMin + "m ago";
        }
        
        long diffHr = diffMin / 60;
        if (diffHr < 24) {
            return "Last seen " + diffHr + "h ago";
        }
        
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
        return "Last seen at " + sdf.format(new java.util.Date(timestamp));
    }

    private void updateChildMarker(boolean isOffline) {
        if (googleMap == null || lastChildLocation == null) return;
        String title = isOffline ? "Last Seen" : childName;

        if (childMarker == null) {
            MarkerOptions options = new MarkerOptions()
                    .position(lastChildLocation)
                    .title(title);
            if (isOffline) {
                options.icon(BitmapDescriptorFactory.fromBitmap(createLastSeenMarkerBitmap()));
                options.anchor(0.5f, 0.9f); // Pointing to the tip
            } else {
                options.icon(BitmapDescriptorFactory.fromBitmap(createInitialsBitmap(childName)));
                options.anchor(0.5f, 0.5f);
            }
            childMarker = googleMap.addMarker(options);
        } else {
            childMarker.setPosition(lastChildLocation);
            childMarker.setTitle(title);
            if (isOffline) {
                childMarker.setIcon(BitmapDescriptorFactory.fromBitmap(createLastSeenMarkerBitmap()));
                childMarker.setAnchor(0.5f, 0.9f);
            } else {
                childMarker.setIcon(BitmapDescriptorFactory.fromBitmap(createInitialsBitmap(childName)));
                childMarker.setAnchor(0.5f, 0.5f);
            }
        }

        if (isOffline) {
            childMarker.showInfoWindow();
        } else {
            childMarker.hideInfoWindow();
        }
    }

    /**
     * Generates a custom red teardrop pin pointing down.
     * Dimensions are set to ~32dp x ~44dp so it's not overly large on the map.
     */
    private Bitmap createLastSeenMarkerBitmap() {
        int widthDp = 32;
        int heightDp = 44;
        float density = getResources().getDisplayMetrics().density;
        int w = (int) (widthDp * density);
        int h = (int) (heightDp * density);

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. Draw a soft shadow at the bottom tip
        paint.setColor(0x33000000); // semi-transparent black
        canvas.drawOval(w * 0.3f, h * 0.85f, w * 0.7f, h * 0.98f, paint);

        // 2. Draw the pin teardrop path (red)
        paint.setColor(0xFFEA4335); // Google Maps red
        android.graphics.Path path = new android.graphics.Path();
        float radius = w / 2f;
        // Circle arc from left to right
        path.arcTo(0, 0, w, w, 180, 180, true);
        // Triangle tip pointing down to (w/2, h * 0.9f)
        path.lineTo(w / 2f, h * 0.9f);
        path.lineTo(0, radius);
        path.close();
        canvas.drawPath(path, paint);

        // 3. Draw a white inner border around the circle part for contrast
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(w / 2f, w / 2f, w / 2f - 0.75f * density, paint);

        // 4. Draw the inner dark circle
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFB31412); // darker red for the center dot
        canvas.drawCircle(w / 2f, w / 2f, w * 0.18f, paint);

        return bitmap;
    }

    private Bitmap createInitialsBitmap(String name) {
        return createInitialsBitmap(name, 0xFF1A73E8);
    }

    /**
     * Creates a filled circle bitmap with up to 2 initials of the child's name in white.
     * e.g. "hamza" → "H", "John Doe" → "JD"
     */
    private Bitmap createInitialsBitmap(String name, int circleColor) {
        int sizeDp = 48;
        float density = getResources().getDisplayMetrics().density;
        int sizePx = (int) (sizeDp * density);

        // Extract up to 2 initials
        StringBuilder initials = new StringBuilder();
        String[] parts = name.trim().split("\\s+");
        for (String part : parts) {
            if (!part.isEmpty()) initials.append(Character.toUpperCase(part.charAt(0)));
            if (initials.length() == 2) break;
        }
        if (initials.length() == 0) initials.append("?");

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Filled circle with dynamic color
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(circleColor);
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circlePaint);

        // White border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - density, borderPaint);

        // White initials text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(sizePx * 0.38f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(initials.toString(), sizePx / 2f, textY, textPaint);

        return bitmap;
    }

    // ── Lifecycle forwarding (required for MapView) ────────────────────────────

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle mapViewBundle = outState.getBundle(MAP_VIEW_BUNDLE_KEY);
        if (mapViewBundle == null) {
            mapViewBundle = new Bundle();
            outState.putBundle(MAP_VIEW_BUNDLE_KEY, mapViewBundle);
        }
        mapView.onSaveInstanceState(mapViewBundle);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (googleMap != null && childDeviceId != null && !childDeviceId.isEmpty()) {
            startLocationListener(false); // Re-attach listener on resume, but don't force a fresh request
        }
    }

    @Override
    protected void onPause() {
        if (locationListener != null && locationRef != null) {
            try { locationRef.removeEventListener(locationListener); } catch (Exception ignored) {}
        }
        if (locationRef != null) {
            locationRef.keepSynced(false);
        }
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Cancel any pending timeout runnables to prevent memory leaks
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }
        // Detach Firebase listener to prevent memory leaks
        if (locationListener != null && locationRef != null) {
            locationRef.removeEventListener(locationListener);
        }
        if (locationRef != null) {
            locationRef.keepSynced(false);
        }
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
