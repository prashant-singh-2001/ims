package com.piecetrack.ui.catalogue;

import com.piecetrack.domain.AttributeDefinition;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;

/** JavaFX-observable row wrapper around {@link AttributeDefinition} for TableView binding. */
public class AttributeDefinitionRow {

    private final long id;
    private final SimpleStringProperty name;
    private final SimpleBooleanProperty active;

    public AttributeDefinitionRow(AttributeDefinition definition) {
        this.id = definition.id();
        this.name = new SimpleStringProperty(definition.name());
        this.active = new SimpleBooleanProperty(definition.active());
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
