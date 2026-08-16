package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.ItemPhoto;
import com.furnitureims.money.Money;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/**
 * Item model editor (FR-ITEM-01/02/03; docs/03-screens.md 4.2): Details, Specification
 * and Photos tabs. Reached from {@link ItemModelListController} via {@link #openForNew()}
 * or {@link #openForEdit(long)}, which set pending state on this singleton controller
 * before navigating - the same pattern the setup wizard and login/lock screen use, since
 * a fresh FXML load re-invokes {@code initialize()} on the same Spring-managed instance.
 */
@Component
public class ItemModelEditorController {

    private final ItemModelService itemModelService;
    private final CategoryService categoryService;
    private final SceneRouter sceneRouter;

    @FXML private Label titleLabel;
    @FXML private Label errorLabel;

    @FXML private TextField modelCodeField;
    @FXML private TextField modelNameField;
    @FXML private ComboBox<Category> categoryCombo;
    @FXML private TextField hsnCodeField;
    @FXML private TextField gstRateField;
    @FXML private TextField defaultPriceField;
    @FXML private CheckBox activeCheck;
    @FXML private TextArea notesArea;

    @FXML private TextField lengthField;
    @FXML private TextField widthField;
    @FXML private TextField heightField;
    @FXML private TextField materialField;
    @FXML private TextField finishField;
    @FXML private TextField colourField;

    @FXML private Tab photosTab;
    @FXML private FlowPane photoFlowPane;

    private Long editingId;
    private Long pendingOpenId;
    private boolean pendingIsNew = true;

    public ItemModelEditorController(ItemModelService itemModelService, CategoryService categoryService,
                                      SceneRouter sceneRouter) {
        this.itemModelService = itemModelService;
        this.categoryService = categoryService;
        this.sceneRouter = sceneRouter;
    }

    public void openForNew() {
        pendingOpenId = null;
        pendingIsNew = true;
    }

    public void openForEdit(long itemModelId) {
        pendingOpenId = itemModelId;
        pendingIsNew = false;
    }

    @FXML
    private void initialize() {
        categoryCombo.setItems(FXCollections.observableArrayList(categoryService.listAll()));
        categoryCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category category) {
                return category == null ? "" : category.name();
            }

            @Override
            public Category fromString(String string) {
                return null;
            }
        });

        errorLabel.setText("");
        if (pendingIsNew) {
            resetForNew();
        } else {
            loadForEdit(pendingOpenId);
        }
    }

    private void resetForNew() {
        editingId = null;
        titleLabel.setText("New Item Model");
        modelCodeField.clear();
        modelNameField.clear();
        categoryCombo.setValue(null);
        hsnCodeField.clear();
        gstRateField.clear();
        defaultPriceField.clear();
        activeCheck.setSelected(true);
        notesArea.clear();
        lengthField.clear();
        widthField.clear();
        heightField.clear();
        materialField.clear();
        finishField.clear();
        colourField.clear();
        photosTab.setDisable(true);
        photoFlowPane.getChildren().clear();
    }

    private void loadForEdit(long id) {
        ItemModel model = itemModelService.findById(id)
                .orElseThrow(() -> new IllegalStateException("Item model not found: " + id));
        editingId = id;
        titleLabel.setText("Edit Item Model - " + model.modelCode());
        modelCodeField.setText(model.modelCode());
        modelNameField.setText(model.modelName());
        categoryService.findById(model.categoryId()).ifPresent(categoryCombo::setValue);
        hsnCodeField.setText(model.hsnCode());
        gstRateField.setText(model.gstRate().toPlainString());
        defaultPriceField.setText(model.defaultSalePrice() == null ? "" : model.defaultSalePrice().rupees().toPlainString());
        activeCheck.setSelected(model.active());
        notesArea.setText(model.notes());
        lengthField.setText(model.lengthCm() == null ? "" : model.lengthCm().toPlainString());
        widthField.setText(model.widthCm() == null ? "" : model.widthCm().toPlainString());
        heightField.setText(model.heightCm() == null ? "" : model.heightCm().toPlainString());
        materialField.setText(model.material());
        finishField.setText(model.finish());
        colourField.setText(model.colour());
        photosTab.setDisable(false);
        refreshPhotos();
    }

    @FXML
    private void onSaveClicked() {
        try {
            if (editingId == null) {
                ItemModel draft = buildModelFromForm(0);
                long newId = itemModelService.create(draft);
                editingId = newId;
                titleLabel.setText("Edit Item Model - " + draft.modelCode());
                photosTab.setDisable(false);
                refreshPhotos();
                errorLabel.setText("Saved. You can now add photos below.");
            } else {
                itemModelService.update(buildModelFromForm(editingId));
                errorLabel.setText("Saved.");
            }
        } catch (IllegalArgumentException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private ItemModel buildModelFromForm(long id) {
        Category category = categoryCombo.getValue();
        if (category == null) {
            throw new IllegalArgumentException("Please select a category.");
        }
        BigDecimal gstRate = parseRequiredDecimal(gstRateField.getText(), "GST rate");
        BigDecimal length = parseOptionalDecimal(lengthField.getText(), "Length");
        BigDecimal width = parseOptionalDecimal(widthField.getText(), "Width");
        BigDecimal height = parseOptionalDecimal(heightField.getText(), "Height");
        Money defaultPrice = defaultPriceField.getText() == null || defaultPriceField.getText().isBlank()
                ? null : Money.ofRupees(parseRequiredDecimal(defaultPriceField.getText(), "Default price"));

        return new ItemModel(id, requireText(modelCodeField.getText(), "Model code"),
                requireText(modelNameField.getText(), "Model name"), category.id(),
                requireText(hsnCodeField.getText(), "HSN code"), gstRate, length, width, height,
                nullIfBlank(materialField.getText()), nullIfBlank(finishField.getText()),
                nullIfBlank(colourField.getText()), defaultPrice, activeCheck.isSelected(),
                nullIfBlank(notesArea.getText()));
    }

    private void refreshPhotos() {
        photoFlowPane.getChildren().clear();
        if (editingId == null) {
            return;
        }
        List<ItemPhoto> photos = itemModelService.photosFor(editingId);
        for (ItemPhoto photo : photos) {
            photoFlowPane.getChildren().add(buildPhotoCard(photo));
        }
    }

    private Node buildPhotoCard(ItemPhoto photo) {
        Path file = itemModelService.photoFile(photo);
        ImageView imageView = new ImageView(new Image(file.toUri().toString(), 140, 140, true, true, true));
        imageView.setFitWidth(140);
        imageView.setFitHeight(140);
        imageView.setPreserveRatio(true);

        Button primaryButton = new Button(photo.primary() ? "Primary" : "Set Primary");
        primaryButton.setDisable(photo.primary());
        primaryButton.setOnAction(e -> {
            itemModelService.setPrimaryPhoto(photo);
            refreshPhotos();
        });

        Button removeButton = new Button("Remove");
        removeButton.setOnAction(e -> {
            itemModelService.removePhoto(photo);
            refreshPhotos();
        });

        VBox card = new VBox(6, imageView, primaryButton, removeButton);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(8));
        card.setStyle("-fx-border-color: #ccc; -fx-border-radius: 4; -fx-background-color: white;");
        return card;
    }

    @FXML
    private void onAddPhotoClicked() {
        if (editingId == null) {
            errorLabel.setText("Save the model first, then add photos.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(sceneRouter.stage());
        if (file == null) {
            return;
        }
        try {
            itemModelService.addPhoto(editingId, file.toPath());
            errorLabel.setText("");
            refreshPhotos();
        } catch (IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/catalogue/item-model-list.fxml");
    }

    private static BigDecimal parseRequiredDecimal(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static BigDecimal parseOptionalDecimal(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String requireText(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return text.trim();
    }

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
