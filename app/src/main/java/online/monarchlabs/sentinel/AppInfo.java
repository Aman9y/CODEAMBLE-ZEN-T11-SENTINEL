package online.monarchlabs.sentinel;

import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

public class AppInfo implements Parcelable {
    public String name;
    public String packageName;
    public String iconBase64;
    public boolean blocked;
    public transient Drawable icon;

    // NEW: For remote blocking
    public String sourceDeviceId;    // Which device this app came from
    public String sourceDeviceName;  // Name of source device
    public boolean canBeBlocked;     // Whether this app can be remotely blocked

    // NEW: Additional fields for timer functionality
    public String category;
    public Long versionCode;
    public String versionName;
    public boolean isSystemApp;
    public boolean isSelected;

    public AppInfo() {
        // Default constructor required for Firebase
    }

    public AppInfo(String name, String packageName, String iconBase64, boolean blocked) {
        this.name = name;
        this.packageName = packageName;
        this.iconBase64 = iconBase64;
        this.blocked = blocked;
        this.canBeBlocked = false; // Default: local apps can't be remotely blocked
        this.isSystemApp = false;
        this.isSelected = false;
    }

    public AppInfo(String name, String packageName, Drawable icon, boolean blocked) {
        this.name = name;
        this.packageName = packageName;
        this.icon = icon;
        this.blocked = blocked;
        this.canBeBlocked = false;
        this.isSystemApp = false;
        this.isSelected = false;
    }

    // Constructor for remote apps that can be blocked
    public AppInfo(String name, String packageName, String iconBase64, boolean blocked,
                   String sourceDeviceId, String sourceDeviceName) {
        this.name = name;
        this.packageName = packageName;
        this.iconBase64 = iconBase64;
        this.blocked = blocked;
        this.sourceDeviceId = sourceDeviceId;
        this.sourceDeviceName = sourceDeviceName;
        this.canBeBlocked = true; // Remote apps can be blocked
        this.isSystemApp = false;
        this.isSelected = false;
    }

    // Parcelable implementation
    protected AppInfo(Parcel in) {
        name = in.readString();
        packageName = in.readString();
        iconBase64 = in.readString();
        blocked = in.readByte() != 0;
        sourceDeviceId = in.readString();
        sourceDeviceName = in.readString();
        canBeBlocked = in.readByte() != 0;
        category = in.readString();
        versionCode = in.readByte() == 0 ? null : in.readLong();
        versionName = in.readString();
        isSystemApp = in.readByte() != 0;
        isSelected = in.readByte() != 0;
    }

    public static final Creator<AppInfo> CREATOR = new Creator<AppInfo>() {
        @Override
        public AppInfo createFromParcel(Parcel in) {
            return new AppInfo(in);
        }

        @Override
        public AppInfo[] newArray(int size) {
            return new AppInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(packageName);
        dest.writeString(iconBase64);
        dest.writeByte((byte) (blocked ? 1 : 0));
        dest.writeString(sourceDeviceId);
        dest.writeString(sourceDeviceName);
        dest.writeByte((byte) (canBeBlocked ? 1 : 0));
        dest.writeString(category);
        if (versionCode == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(versionCode);
        }
        dest.writeString(versionName);
        dest.writeByte((byte) (isSystemApp ? 1 : 0));
        dest.writeByte((byte) (isSelected ? 1 : 0));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AppInfo appInfo = (AppInfo) obj;
        return packageName != null ? packageName.equals(appInfo.packageName) : appInfo.packageName == null;
    }

    @Override
    public int hashCode() {
        return packageName != null ? packageName.hashCode() : 0;
    }
}