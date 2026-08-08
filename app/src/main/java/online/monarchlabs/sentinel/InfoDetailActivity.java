package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import online.monarchlabs.sentinel.utils.InfoContentRepository;

public class InfoDetailActivity extends BaseActivity {

    public static final String EXTRA_CONTENT_KEY = "content_key";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info_detail);

        setupToolbar();
        loadContent();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false); // We show title in large text
        }
    }

    private void loadContent() {
        String key = getIntent().getStringExtra(EXTRA_CONTENT_KEY);
        if (key == null)
            key = InfoContentRepository.KEY_HELP;

        // Get content from repository
        String title = InfoContentRepository.getTitle(key);
        String content = InfoContentRepository.getContent(key);

        // Set text
        TextView tvPageTitle = findViewById(R.id.tvPageTitle);
        TextView tvContent = findViewById(R.id.tvContent);

        if (tvPageTitle != null) {
            tvPageTitle.setText(title);
        }

        if (tvContent != null) {
            tvContent.setText(content);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
