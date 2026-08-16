package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;

/** JavaFX-observable row wrapper around {@link Category} for TableView binding. */
public class CategoryRow {

    private final long id;
    private final SimpleStringProperty name;
    private final SimpleBooleanProperty active;

    public CategoryRow(Category category) {
        this.id = category.id();
        this.name = new SimpleStringProperty(category.name());
        this.active = new SimpleBooleanProperty(category.active());
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
