package com.piecetrack.ui.catalogue;

import com.piecetrack.domain.StorageLocation;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;

/** JavaFX-observable row wrapper around {@link StorageLocation} for TableView binding. */
public class StorageLocationRow {

    private final long id;
    private final SimpleStringProperty name;
    private final SimpleBooleanProperty active;

    public StorageLocationRow(StorageLocation location) {
        this.id = location.id();
        this.name = new SimpleStringProperty(location.name());
        this.active = new SimpleBooleanProperty(location.active());
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name.get();
    }

    public SimpleStringProperty nameProperty() {
        return name;
    }

    public boolean getActive() {
        return active.get();
    }

    public SimpleBooleanProperty activeProperty() {
        return active;
    }
}
