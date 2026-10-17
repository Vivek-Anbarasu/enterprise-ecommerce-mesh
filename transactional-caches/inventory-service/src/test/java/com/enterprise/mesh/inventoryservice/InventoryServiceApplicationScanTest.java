package com.enterprise.mesh.inventoryservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mesh.inventoryservice.controller.InventoryController;
import com.enterprise.mesh.inventoryservice.repository.InventoryItemRepository;
import com.enterprise.mesh.inventoryservice.service.InventoryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Guards the component-scan base package.
 *
 * <p>{@code @SpringBootApplication} component-scans the package its own class is
 * declared in, not the directory it happens to sit in. So if the application class
 * declares a package that is not a prefix of its siblings' packages, the application
 * still compiles, still boots, still passes every unit test in this module - and
 * silently registers no controllers, no services and no repositories at all.
 *
 * <p>That is not hypothetical here: this class was the only file in the module whose
 * declared package did not match its directory, so nothing in
 * {@code com.enterprise.mesh.inventoryservice} was ever scanned. The unit tests here
 * construct domain objects directly and never start a context, so nothing noticed.
 *
 * <p>This test starts one. It is the only kind of test that can fail on this class of
 * mistake, which is why it exists despite the module otherwise having no context test.
 */
@SpringBootTest(properties = {
        // H2 rather than the PostgreSQL the service points at by default, so this is a
        // slice that needs no container. MODE=PostgreSQL keeps the DDL valid; Flyway is
        // off so Hibernate creates the schema instead of running the real migrations.
        "spring.datasource.url=jdbc:h2:mem:inventory;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Redis and Kafka are deliberately left auto-configured rather than excluded.
        // Neither connects eagerly, but MeshMessagingAutoConfiguration depends on
        // KafkaProperties, so excluding Kafka breaks the context for the opposite of
        // the intended reason.
        "mesh.tenant.allow-all=true"
})
class InventoryServiceApplicationScanTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the controller is a bean, so its endpoints are actually mapped")
    void controllerIsScanned() {
        assertThat(context.getBeanNamesForType(InventoryController.class))
                .as("InventoryController must be component-scanned. If this is empty, the "
                        + "@SpringBootApplication class declares a package that does not contain "
                        + "its siblings, and the service serves no endpoints while looking healthy.")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the business service is a bean")
    void serviceIsScanned() {
        assertThat(context.getBeanNamesForType(InventoryService.class)).isNotEmpty();
    }

    @Test
    @DisplayName("Spring Data repositories are discovered")
    void repositoriesAreScanned() {
        assertThat(context.getBeanNamesForType(InventoryItemRepository.class))
                .as("Repositories are found by scanning from the @SpringBootApplication package, "
                        + "so a mismatched application package hides them too.")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the application class sits in the same package as the code it must scan")
    void applicationPackageContainsItsSiblings() throws ClassNotFoundException {
        // The root cause, asserted directly rather than inferred from its symptoms.
        // Referenced by canonical name so this test compiles whether or not the class
        // is currently where it should be - a plain type reference would not compile
        // from this package while the mismatch exists, which would hide the very
        // regression it exists to catch.
        assertThat(Class.forName("com.enterprise.mesh.inventoryservice.InventoryServiceApplication"))
                .as("InventoryServiceApplication lives under com/enterprise/mesh/inventoryservice/ "
                        + "on disk, so it must declare that package. Anything else narrows the "
                        + "component scan to a subtree containing none of its siblings.")
                .isNotNull();
    }

    @Test
    @DisplayName("the application class is not shadowing its siblings from another package")
    void noStrayApplicationClassInASiblingPackage() {
        // Guards the same thing from the other side: an application class left in a
        // neighbouring package after a rename. Component scanning follows the declared
        // package, so a file's directory is irrelevant - only this declaration is.
        assertThatThrownBy(() -> Class.forName("com.enterprise.mesh.inventory.InventoryServiceApplication"))
                .as("A copy of the application class under com.enterprise.mesh.inventory would "
                        + "scan an empty subtree and register nothing.")
                .isInstanceOf(ClassNotFoundException.class);
    }
}