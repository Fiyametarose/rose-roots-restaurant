module com.roseroots.customer {
    requires javafx.controls;
    requires java.net.http;
    requires java.prefs;
    requires com.google.gson;

    exports com.roseroots.customer.app to javafx.graphics;
}
