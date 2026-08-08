package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import online.monarchlabs.sentinel.utils.InfoContentRepository;

public class TermsAndServicesActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terms_and_services);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());

        TextView content = findViewById(R.id.tvTermsContent);
        content.setText(InfoContentRepository.getContent(InfoContentRepository.KEY_TERMS));
    }
}
