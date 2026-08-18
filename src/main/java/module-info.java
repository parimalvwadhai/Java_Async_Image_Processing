module com.image.imageprocessing {
    // transitive: exported types (e.g. DrawMultipleImagesOnCanvas.createCanvas) expose
    // javafx.graphics types such as Canvas and Stage in their public signatures.
    requires transitive javafx.graphics;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;
    requires java.desktop;
    requires javafx.swing;

    opens com.image.imageprocessing to javafx.fxml;
    exports com.image.imageprocessing;
    exports com.image.imageprocessing.image;
}