package com.piecetrack.ui.catalogue;

import com.piecetrack.domain.AttributeDefinition;
import com.piecetrack.domain.Category;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.ItemPhoto;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelAttributeRepository;
import com.piecetrack.service.AttributeDefinitionService;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.SettingsService;
import com.piecetrack.ui.HasScreenTitle;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item model editor (FR-ITEM-01/02/03; docs/03-screens.md 4.2): Details, Specification
 * and Photos tabs. Reached from {@link ItemModelListController} via {@link #openForNew()}
 * or {@link #openForEdit(long)}, which set pending state on this singleton controller
 * before navigating - the same pattern the setup wizard and login/lock screen use, since
 * a fresh FXML load re-invokes {@code initialize()} on the same Spring-managed instance.
 */
@Component
public class ItemModelEditorController implements HasScreenTitle {

    private final ItemModelService itemModelService;
    private final CategoryService categoryService;
    private final SettingsService settingsService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final ItemModelAttributeRepository itemModelAttributeRepository;
    private final SceneRouter sceneRouter;

    /** M10: the shell top bar's title for this screen - see {@link HasScreenTitle}. Not
     *  FXML-bound (there is no title Label in this screen's own FXML any more); this
     *  controller owns the text directly and the shell binds to it after each navigation. */
    private final StringProperty screenTitle = new SimpleStringProperty("");

    @FXML private Label errorLabel;

    @FXML private TextField modelCodeField;
    @FXML private TextField modelNameField;
    @FXML private ComboBox<Category> categoryCombo;
    @FXML private Label hsnCodeLabel;
    @FXML private TextField hsnCodeField;
    @FXML private Label gstRateLabel;
    @FXML private TextField gstRateField;
    @FXML private TextField defaultPriceField;
    @FXML private CheckBox activeCheck;
    @FXML private TextArea notesArea;

    @FXML private GridPane attributesGrid;

    @FXML private Tab photosTab;
    @FXML private FlowPane photoFlowPane;

    /** Built fresh in {@link #initialize()} from whichever {@link AttributeDefinition}s are
     *  currently active (M15) - one text field per definition, keyed by definition id so a
     *  value survives a definition being renamed after it was entered. */
    private final Map<Long, TextField> attributeFields = new LinkedHashMap<>();

    private Long editingId;
    private Long pendingOpenId;
    private boolean pendingIsNew = true;

    public ItemModelEditorController(ItemModelService itemModelService, CategoryService categoryService,
                                      SettingsService settingsService,
                                      AttributeDefinitionService attributeDefinitionService,
                                      ItemModelAttributeRepository itemModelAttributeRepository,
                                      SceneRouter sceneRouter) {
        this.itemModelService = itemModelService;
        this.categoryService = categoryService;
        this.settingsService = settingsService;
        this.attributeDefinitionService = attributeDefinitionService;
        this.itemModelAttributeRepository = itemModelAttributeRepository;
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

    @Override
    public ReadOnlyStringProperty screenTitleProperty() {
        return screenTitle;
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

        buildAttributesGrid();

        errorLabel.setText("");
        applyGstVisibility();
        if (pendingIsNew) {
            resetForNew();
        } else {
            loadForEdit(pendingOpenId);
        }
    }

    /** M15: one Label+TextField row per active {@link AttributeDefinition}, in {@code
     *  display_order} - replaces the six fixed fields (length/width/height/material/finish/
     *  colour) this tab hardcoded before the generic rename. Rebuilt every time the screen
     *  loads rather than once, since a definition could have been added, renamed or
     *  deactivated from Categories & Locations since this screen was last opened. */
    private void buildAttributesGrid() {
        attributesGrid.getChildren().clear();
        attributeFields.clear();
        List<AttributeDefinition> definitions = attributeDefinitionService.listActive();
        for (int row = 0; row < definitions.size(); row++) {
            AttributeDefinition definition = definitions.get(row);
            Label label = new Label(definition.name());
            TextField field = new TextField();
            GridPane.setRowIndex(label, row);
            GridPane.setColumnIndex(label, 0);
            GridPane.setRowIndex(field, row);
            GridPane.setColumnIndex(field, 1);
            attributesGrid.getChildren().addAll(label, field);
            attributeFields.put(definition.id(), field);
        }
    }

    /** M10: see NewSaleController.applyGstVisibility for the reasoning - one pass at load
     *  time, since the toggle only ever changes from Settings. */
    private void applyGstVisibility() {
        boolean gstEnabled = settingsService.isGstEnabled();
        hsnCodeLabel.setVisible(gstEnabled);
        hsnCodeLabel.setManaged(gstEnabled);
        hsnCodeField.setVisible(gstEnabled);
        hsnCodeField.setManaged(gstEnabled);
        gstRateLabel.setVisible(gstEnabled);
        gstRateLabel.setManaged(gstEnabled);
        gstRateField.setVisible(gstEnabled);
        gstRateField.setManaged(gstEnabled);
    }

    private void resetForNew() {
        editingId = null;
        screenTitle.set("New Item Model");
        modelCodeField.clear();
        modelNameField.clear();
        categoryCombo.setValue(null);
        hsnCodeField.clear();
        gstRateField.clear();
        defaultPriceField.clear();
        activeCheck.setSelected(true);
        notesArea.clear();
        attributeFields.values().forEach(TextField::clear);
        photosTab.setDisable(true);
        photoFlowPane.getChildren().clear();
    }

    private void loadForEdit(long id) {
        ItemModel model = itemModelService.findById(id)
                .orElseThrow(() -> new IllegalStateException("Item model not found: " + id));
        editingId = id;
        screenTitle.set("Edit Item Model - " + model.modelCode());
        modelCodeField.setText(model.modelCode());
        modelNameField.setText(model.modelName());
        categoryService.findById(model.categoryId()).ifPresent(categoryCombo::setValue);
        hsnCodeField.setText(model.hsnCode());
        gstRateField.setText(model.gstRate().toPlainString());
        defaultPriceField.setText(model.defaultSalePrice() == null ? "" : model.defaultSalePrice().rupees().toPlainString());
        activeCheck.setSelected(model.active());
        notesArea.setText(model.notes());

        Map<Long, String> values = itemModelAttributeRepository.findValuesByItemModelId(id);
        attributeFields.forEach((definitionId, field) -> field.setText(values.getOrDefault(definitionId, "")));

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
                saveAttributeValues(newId);
                screenTitle.set("Edit Item Model - " + draft.modelCode());
                photosTab.setDisable(false);
                refreshPhotos();
                errorLabel.setText("Saved. You can now add photos below.");
            } else {
                itemModelService.update(buildModelFromForm(editingId));
                saveAttributeValues(editingId);
                errorLabel.setText("Saved.");
            }
        } catch (IllegalArgumentException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    /** Attribute values need a real {@code item_model_id}, so a brand-new model must be
     *  created first ({@link #onSaveClicked} does that above) before its values can be
     *  saved against it - the two steps happen in the same click, just in that order. */
    private void saveAttributeValues(long itemModelId) {
        Map<Long, String> values = new LinkedHashMap<>();
        attributeFields.forEach((definitionId, field) -> values.put(definitionId, field.getText()));
        itemModelAttributeRepository.saveValues(itemModelId, values);
    }

    private ItemModel buildModelFromForm(long id) {
        Category category = categoryCombo.getValue();
        if (category == null) {
            throw new IllegalArgumentException("Please select a category.");
        }
        // M10: HSN/GST rate are only a hard requirement while GST is on - off, a blank field
        // flows through as null and ItemModelService.applyGstSentinelIfDisabled fills in the
        // sentinel, rather than this method rejecting the save itself before the service
        // ever gets a chance to.
        boolean gstEnabled = settingsService.isGstEnabled();
        BigDecimal gstRate = gstEnabled
                ? parseRequiredDecimal(gstRateField.getText(), "GST rate")
                : parseOptionalDecimal(gstRateField.getText(), "GST rate");
        String hsnCode = gstEnabled
                ? requireText(hsnCodeField.getText(), "HSN code")
                : nullIfBlank(hsnCodeField.getText());
        Money defaultPrice = defaultPriceField.getText() == null || defaultPriceField.getText().isBlank()
                ? null : Money.ofRupees(parseRequiredDecimal(defaultPriceField.getText(), "Default price"));

        return new ItemModel(id, requireText(modelCodeField.getText(), "Model code"),
                requireText(modelNameField.getText(), "Model name"), category.id(),
                hsnCode, gstRate, defaultPrice, activeCheck.isSelected(),
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
        card.getStyleClass().add("photo-card");
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
