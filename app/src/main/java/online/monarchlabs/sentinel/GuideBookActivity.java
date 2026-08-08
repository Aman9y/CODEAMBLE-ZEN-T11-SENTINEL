package online.monarchlabs.sentinel;

import android.os.Bundle;
import android.view.View;
import android.widget.ExpandableListView;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import online.monarchlabs.sentinel.adapters.GuideExpandableListAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class GuideBookActivity extends BaseActivity {

        GuideExpandableListAdapter listAdapter;
        ExpandableListView expListView;
        List<String> listDataHeader;
        HashMap<String, String> listDataChild;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.activity_guide_book);

                // Initialize views
                expListView = findViewById(R.id.expandableListView);
                ImageView btnBack = findViewById(R.id.btnBack);

                // Prepare list data
                prepareListData();

                // Create adapter
                listAdapter = new GuideExpandableListAdapter(this, listDataHeader, listDataChild);

                // Set adapter
                expListView.setAdapter(listAdapter);

                // Back button
                btnBack.setOnClickListener(v -> finish());

                // Expand first group by default for better UX
                // expListView.expandGroup(0);
        }

        private void prepareListData() {
                listDataHeader = new ArrayList<>();
                listDataChild = new HashMap<>();

                // Adding headers
                listDataHeader.add("How to Add Child");
                listDataHeader.add("What is Detailed Stats");
                listDataHeader.add("What is App Limits");
                listDataHeader.add("What is Bell Icon on the Dashboard");
                listDataHeader.add("What is Timer Page");
                listDataHeader.add("What is on Settings Page");

                // Adding content (Exact text requested by user)

                // 1. How to Add Child
                String p1 = "PARENT SIDE:\n" +
                                "Click on Manage Device or Add Button to open QR code so that child could scan QR.\n\n"
                                +
                                "CHILD SIDE:\n" +
                                "Enter Name, Grant Permissions asked, and then scan the QR of the parent that wants to connect to the child.";

                // 2. Detailed Stats
                String p2 = "In basic works, it shows 7 days usage data of child device.\n" +
                                "You can see exactly how much time was spent on each app daily.";

                // 3. App Limits
                String p3 = "TIMER:\n" +
                                "To set timer on child device: Set Limit, Add Time. The timer will run on those apps until you remove it. "
                                +
                                "It applies everyday for the amount of time you set it.\n\n" +
                                "NOTE - EVERYDAY AS THE TIMER EXPIRES IT WON'T BLOCK APPS BUT YOU CAN SEE THAT TIMER IS EXPIRED AND BLOCK THOSE APPS.\n\n"
                                +
                                "BLOCKING:\n" +
                                "Simply select apps to block and they will be blocked until you unblock it.";

                // 4. Bell Icon
                String p4 = "PERMISSION STATUS:\n" +
                                "Gives real time status of permission of child device as if they are enabled or disabled. "
                                +
                                "This helps you know if permissions are running as they are the mandatory thing to make our service work.\n\n"
                                +
                                "APP STATUS:\n" +
                                "You can know which apps are installed and uninstalled on the child device in real time with date and time.";

                // 5. Timer Page
                String p5 = "Timer page shows all the apps you set timer on and how much time is left before time expires.";

                // 6. Settings Page
                String p6 = "UNINSTALL PROTECTION BUTTON:\n" +
                                "This feature uses Android Device Admin to add a verification step before Sentinel can be uninstalled from the child device.\n\n" +
                                "WHY IS IT NEEDED?\n" +
                                "It gives parents live protection status and lets them send a setup request to the linked child device.\n\n" +
                                "IMPORTANT:\n" +
                                "The dashboard switch reflects the current Device Admin state. Android confirms each activation or settings change on the child device.\n\n"
                                +
                                "Also contains your Details and our Terms and Conditions.";

                // Map Header -> Content
                listDataChild.put(listDataHeader.get(0), p1);
                listDataChild.put(listDataHeader.get(1), p2);
                listDataChild.put(listDataHeader.get(2), p3);
                listDataChild.put(listDataHeader.get(3), p4);
                listDataChild.put(listDataHeader.get(4), p5);
                listDataChild.put(listDataHeader.get(5), p6);
        }
}
