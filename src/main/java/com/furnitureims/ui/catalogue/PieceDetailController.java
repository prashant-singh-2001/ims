package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.PiecePhoto;
import com.furnitureims.domain.StockMovement;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PiecePhotoService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Piece detail (FR-PIECE-07; docs/03-screens.md 4.4) - what settles a physical stock
 * discrepancy, so the movement history is shown in full, never summarised.
 */
@Component
public class PieceDetailController {

    private final PieceService pieceService;
    private final ItemModelService itemModelService;
    private final StorageLocationService storageLocationService;
    private final PiecePhotoService piecePhotoService;
    private final SceneRouter sceneRouter;

    @FXML private Label tagLabel;
    @FXML private Label modelLabel;
    @FXML private Label stateLabel;
    @FXML private Label locationLabel;
    @FXML private Label costLabel;
    @FXML private Label daysLabel;
    @FXML private Label sourceLabel;
    @FXML private TextField renameTagField;
    @FXML private Label errorLabel;
    @FXML private FlowPane photoFlowPane;
    @FXML private ListView<String> historyList;

    private long pieceId;

    public PieceDetailController(PieceService pieceService, ItemModelService itemModelService,
                                  StorageLocationService storageLocationService,
                                  PiecePhotoService piecePhotoService, SceneRouter sceneRouter) {
        this.pieceService = pieceService;
        this.itemModelService = itemModelService;
        this.storageLocationService = storageLocationService;
        this.piecePhotoService = piecePhotoService;
        this.sceneRouter = sceneRouter;
    }

    public void openFor(long pieceId) {
        this.pieceId = pieceId;
    }

    @FXML
    private void initialize() {
        errorLabel.setText("");
        reload();
    }

    private void reload() {
        Piece piece = pieceService.findById(pieceId)
                .orElseThrow(() -> new IllegalStateException("Piece not found: " + pieceId));
        ItemModel model = itemModelService.findById(piece.itemModelId())
                .orElseThrow(() -> new IllegalStateException("Item model not found: " + piece.itemModelId()));

        tagLabel.setText(piece.tag());
        modelLabel.setText(model.modelName() + " (" + model.modelCode() + ")");
        stateLabel.setText(piece.state().name() + (piece.stateReason() == null ? "" : " - " + piece.stateReason()));
        locationLabel.setText(piece.locationId() == null ? "-" :
                storageLocationService.findById(piece.locationId()).map(l -> l.name()).orElse("-"));
        costLabel.setText(piece.landedCost().toDisplayString());
        daysLabel.setText(ChronoUnit.DAYS.between(piece.acquiredOn(), LocalDate.now()) + " days (since "
                + piece.acquiredOn() + ")");
        sourceLabel.setText(piece.sourceType().name());
        renameTagField.setText(piece.tag());

        refreshPhotos();

        List<StockMovement> history = pieceService.history(pieceId);
        ObservableList<String> lines = FXCollections.observableArrayList();
        for (StockMovement m : history) {
            lines.add(formatMovement(m));
        }
        historyList.setItems(lines);
    }

    private void refreshPhotos() {
        photoFlowPane.getChildren().clear();
        for (PiecePhoto photo : piecePhotoService.photosFor(pieceId)) {
            photoFlowPane.getChildren().add(buildPhotoCard(photo));
        }
    }

    private Node buildPhotoCard(PiecePhoto photo) {
        Path file = piecePhotoService.photoFile(photo);
        ImageView imageView = new ImageView(new Image(file.toUri().toString(), 140, 140, true, true, true));
        imageView.setFitWidth(140);
        imageView.setFitHeight(140);
        imageView.setPreserveRatio(true);

        Button primaryButton = new Button(photo.primary() ? "Primary" : "Set Primary");
        primaryButton.setDisable(photo.primary());
        primaryButton.setOnAction(e -> {
            piecePhotoService.setPrimaryPhoto(photo);
            refreshPhotos();
        });

        Button removeButton = new Button("Remove");
        removeButton.setOnAction(e -> {
            piecePhotoService.removePhoto(photo);
            refreshPhotos();
        });

        VBox card = new VBox(6, imageView, primaryButton, removeButton);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("photo-card");
        return card;
    }

    @FXML
    private void onAddPhotoClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(sceneRouter.stage());
        if (file == null) {
            return;
        }
        try {
            piecePhotoService.addPhoto(pieceId, file.toPath());
            errorLabel.setText("");
            refreshPhotos();
        } catch (IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private static String formatMovement(StockMovement m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.movedAt()).append("  ").append(m.movementType());
        if (m.fromState() != null && m.toState() != null && m.fromState() != m.toState()) {
            sb.append("  (").append(m.fromState()).append(" -> ").append(m.toState()).append(")");
        }
        if (m.note() != null && !m.note().isBlank()) {
            sb.append("  - ").append(m.note());
        }
        return sb.toString();
    }

    @FXML
    private void onRenameTagClicked() {
        try {
            pieceService.renameTag(pieceId, renameTagField.getText());
            errorLabel.setText("");
            reload();
        } catch (IllegalArgumentException e) {
            errorLabel.setText(e.getMessage());
        }
    }

}
