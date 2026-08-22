package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.AttributeDefinition;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.repository.ItemModelAttributeRepository;
import com.piecetrack.repository.ItemModelSearchCriteria;
import com.piecetrack.repository.ItemModelSummary;
import com.piecetrack.service.AttributeDefinitionService;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.ItemModelService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M15 ("PieceTrack"): user-defined item attributes replacing the hardcoded
 * furniture-specific columns. Covers definition CRUD (with the duplicate-name guard),
 * values round-tripping through {@link ItemModelAttributeRepository}, free-text search
 * hitting a custom attribute value, and V12's backfill of a pre-upgrade shop's existing
 * material/colour data into the new generic mechanism.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M15CustomAttributeTest.TestPathsConfig.class)
class M15CustomAttributeTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m15-test-"));
        }
    }

    @Autowired private AttributeDefinitionService attributeDefinitionService;
    @Autowired private ItemModelAttributeRepository itemModelAttributeRepository;
    @Autowired private ItemModelService itemModelService;
    @Autowired private CategoryService categoryService;

    private long categoryId() {
        return categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
    }

    private long newItemModel(String code) {
        return itemModelService.create(new ItemModel(0, code, "Test Widget " + code, categoryId(), "9403",
                new BigDecimal("18"), null, true, null));
    }

    @Test
    void definitionCanBeCreatedRenamedAndDeactivated() {
        long id = attributeDefinitionService.create("Warranty");
        List<AttributeDefinition> all = attributeDefinitionService.listAll();
        assertTrue(all.stream().anyMatch(d -> d.id() == id && d.name().equals("Warranty") && d.active()));

        attributeDefinitionService.rename(id, "Warranty (months)");
        assertTrue(attributeDefinitionService.listAll().stream()
                .anyMatch(d -> d.id() == id && d.name().equals("Warranty (months)")));

        attributeDefinitionService.setActive(id, false);
        assertTrue(attributeDefinitionService.listActive().stream().noneMatch(d -> d.id() == id));
        assertTrue(attributeDefinitionService.listAll().stream().anyMatch(d -> d.id() == id && !d.active()));
    }

    @Test
    void creatingADuplicateNameIsRejected() {
        attributeDefinitionService.create("Duplicate Test Attribute");
        assertThrows(IllegalArgumentException.class,
                () -> attributeDefinitionService.create("Duplicate Test Attribute"));
    }

    @Test
    void valuesRoundTripThroughTheRepositoryAndBlankValuesAreDropped() {
        long modelId = newItemModel("M15A");
        long voltageId = attributeDefinitionService.create("Voltage");
        long caratId = attributeDefinitionService.create("Carat");

        itemModelAttributeRepository.saveValues(modelId, Map.of(voltageId, "220V", caratId, "  "));

        Map<Long, String> values = itemModelAttributeRepository.findValuesByItemModelId(modelId);
        assertEquals(1, values.size(), "a blank value should not be persisted");
        assertEquals("220V", values.get(voltageId));

        itemModelAttributeRepository.saveValues(modelId, Map.of(voltageId, "110V"));
        Map<Long, String> replaced = itemModelAttributeRepository.findValuesByItemModelId(modelId);
        assertEquals(1, replaced.size(), "saveValues replaces the whole set rather than merging");
        assertEquals("110V", replaced.get(voltageId));
    }

    @Test
    void freeTextSearchMatchesACustomAttributeValue() {
        long modelId = newItemModel("M15B");
        long finishId = attributeDefinitionService.create("Surface Finish");
        itemModelAttributeRepository.saveValues(modelId, Map.of(finishId, "Anodized Matte"));

        List<ItemModelSummary> results = itemModelService.search(
                new ItemModelSearchCriteria("Anodized", null, false));

        assertTrue(results.stream().anyMatch(r -> r.model().id() == modelId),
                "search should find the model via its custom attribute value, not just name/code");
    }

    @Test
    void v12BackfillsAPreUpgradeShopsMaterialAndColourIntoDefinitionsAndValues() throws IOException {
        Path tempRoot = Files.createTempDirectory("piecetrack-m15-upgrade-test-");
        AppPaths appPaths = TestAppPathsFactory.create(tempRoot);

        seedDatabaseAtSchemaVersion9WithMaterialAndColour(appPaths);

        FixedPathConfig.fixedRoot = tempRoot;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PieceTrackApplication.class)
                .sources(FixedPathConfig.class)
                .headless(true)
                .run();
        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            Integer materialDefId = jdbc.queryForObject(
                    "SELECT id FROM attribute_definition WHERE name = 'Material'", Integer.class);
            Integer colourDefId = jdbc.queryForObject(
                    "SELECT id FROM attribute_definition WHERE name = 'Colour'", Integer.class);

            String materialValue = jdbc.queryForObject(
                    "SELECT value FROM item_model_attribute WHERE attribute_definition_id = ?",
                    String.class, materialDefId);
            String colourValue = jdbc.queryForObject(
                    "SELECT value FROM item_model_attribute WHERE attribute_definition_id = ?",
                    String.class, colourDefId);

            assertEquals("Sheesham Wood", materialValue);
            assertEquals("Walnut Brown", colourValue);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attribute_definition WHERE name IN ('Length (cm)', 'Width (cm)', "
                            + "'Height (cm)', 'Finish')", Integer.class))
                    .as("no definition should be created for a column that had no non-null value")
                    .isEqualTo(0);
        } finally {
            context.close();
        }
    }

    @Configuration
    static class FixedPathConfig {
        static Path fixedRoot;

        @Bean
        @Primary
        AppPaths testAppPaths() {
            return TestAppPathsFactory.create(fixedRoot);
        }
    }

    /** Mirrors {@code M9UpgradeTest}'s approach: a standalone Flyway instance migrates only
     *  up to V9 (before the M15 rename), then a plain JDBC insert simulates a real furniture
     *  shop's item_model row with material/colour set but length/width/height/finish left
     *  null - exactly the kind of partially-filled specification data V12 must preserve. */
    private static void seedDatabaseAtSchemaVersion9WithMaterialAndColour(AppPaths appPaths) {
        DataSource dataSource = rawDataSourceFor(appPaths);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long categoryId = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Other'", Long.class);
        jdbc.update("""
                INSERT INTO item_model (model_code, model_name, category_id, hsn_code, gst_rate,
                        material, colour, is_active)
                VALUES ('UPG1', 'Pre-Upgrade Model', ?, '9403', 18, 'Sheesham Wood', 'Walnut Brown', 1)
                """, categoryId);
    }

    private static DataSource rawDataSourceFor(AppPaths appPaths) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + appPaths.databaseFile());
        return dataSource;
    }
}
