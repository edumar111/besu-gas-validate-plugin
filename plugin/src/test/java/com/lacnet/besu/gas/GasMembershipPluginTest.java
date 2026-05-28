package com.lacnet.besu.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelectorFactory;
import com.lacnet.besu.gas.validator.GasMembershipTransactionValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.RpcEndpointService;
import org.hyperledger.besu.plugin.services.TransactionPoolValidatorService;
import org.hyperledger.besu.plugin.services.TransactionSelectionService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GasMembershipPluginTest {

    private static final String CONTRACT_ADDR = "0x1234567890123456789012345678901234567890";

    @Mock private ServiceManager serviceManager;
    @Mock private TransactionSimulationService simulationService;
    @Mock private TransactionSelectionService selectionService;
    @Mock private TransactionPoolValidatorService validatorService;
    @Mock private RpcEndpointService rpcEndpointService;

    private Function<String, String> validConfig;

    @BeforeEach
    void setUp() {
        validConfig = Map.of(
                GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, CONTRACT_ADDR)::get;
    }

    @Test
    void serviceLoaderDescubreElPlugin() {
        ServiceLoader<BesuPlugin> loader = ServiceLoader.load(BesuPlugin.class);
        List<BesuPlugin> found = new ArrayList<>();
        loader.forEach(found::add);
        long ours = found.stream().filter(p -> p instanceof GasMembershipPlugin).count();
        assertEquals(1, ours, "GasMembershipPlugin debe descubrirse exactamente una vez via SPI");
    }

    @Test
    void plugin_tieneNombre() {
        GasMembershipPlugin plugin = new GasMembershipPlugin();
        assertEquals(Optional.of("GasMembershipPlugin"), plugin.getName());
    }

    @Test
    void register_cableaTodoYRegistraElFactory() {
        lenient().when(serviceManager.getService(TransactionSimulationService.class))
                .thenReturn(Optional.of(simulationService));
        lenient().when(serviceManager.getService(TransactionSelectionService.class))
                .thenReturn(Optional.of(selectionService));
        lenient().when(serviceManager.getService(TransactionPoolValidatorService.class))
                .thenReturn(Optional.of(validatorService));
        when(serviceManager.getService(RpcEndpointService.class))
                .thenReturn(Optional.of(rpcEndpointService));

        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        plugin.register(serviceManager);

        // Selector + validator factories + bus quedaron instanciados y registrados.
        assertNotNull(plugin.factory(), "factory del selector debe quedar disponible tras register()");
        assertNotNull(plugin.validatorFactory(), "factory del validator debe quedar disponible tras register()");
        assertNotNull(plugin.eventBus(), "el bus de rechazos debe quedar disponible tras register()");
        assertNotNull(plugin.config(), "config debe quedar cargada tras register()");
        verify(selectionService).registerPluginTransactionSelectorFactory(
                any(GasMembershipTransactionSelectorFactory.class));
        verify(validatorService).registerPluginTransactionValidatorFactory(
                any(GasMembershipTransactionValidatorFactory.class));
        // Los dos métodos JSON-RPC custom se registran en el namespace gasMembership.
        verify(rpcEndpointService).registerRPCEndpoint(eq("gasMembership"), eq("getRejection"), any());
        verify(rpcEndpointService).registerRPCEndpoint(eq("gasMembership"), eq("listRejectionsBySender"), any());
    }

    @Test
    void register_fallaSiTransactionSimulationServiceNoEstaDisponible() {
        when(serviceManager.getService(TransactionSimulationService.class))
                .thenReturn(Optional.empty());

        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.register(serviceManager));
        assertTrue(ex.getMessage().contains("TransactionSimulationService"));
    }

    @Test
    void register_fallaSiTransactionSelectionServiceNoEstaDisponible() {
        when(serviceManager.getService(TransactionSimulationService.class))
                .thenReturn(Optional.of(simulationService));
        when(serviceManager.getService(TransactionSelectionService.class))
                .thenReturn(Optional.empty());

        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.register(serviceManager));
        assertTrue(ex.getMessage().contains("TransactionSelectionService"));
    }

    @Test
    void register_fallaSiTransactionPoolValidatorServiceNoEstaDisponible() {
        when(serviceManager.getService(TransactionSimulationService.class))
                .thenReturn(Optional.of(simulationService));
        when(serviceManager.getService(TransactionSelectionService.class))
                .thenReturn(Optional.of(selectionService));
        when(serviceManager.getService(TransactionPoolValidatorService.class))
                .thenReturn(Optional.empty());

        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.register(serviceManager));
        assertTrue(ex.getMessage().contains("TransactionPoolValidatorService"));
    }

    @Test
    void register_fallaSiRpcEndpointServiceNoEstaDisponible() {
        when(serviceManager.getService(TransactionSimulationService.class))
                .thenReturn(Optional.of(simulationService));
        when(serviceManager.getService(TransactionSelectionService.class))
                .thenReturn(Optional.of(selectionService));
        when(serviceManager.getService(TransactionPoolValidatorService.class))
                .thenReturn(Optional.of(validatorService));
        when(serviceManager.getService(RpcEndpointService.class))
                .thenReturn(Optional.empty());

        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.register(serviceManager));
        assertTrue(ex.getMessage().contains("RpcEndpointService"));
    }

    @Test
    void register_fallaSiFaltaElContractAddress() {
        // Resolver vacío → falta ratelimit.membershipContract → IllegalStateException de la config.
        GasMembershipPlugin plugin = new GasMembershipPlugin(key -> null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.register(serviceManager));
        assertTrue(ex.getMessage().contains(GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT));
    }

    @Test
    void start_yStop_NoLanzan() {
        GasMembershipPlugin plugin = new GasMembershipPlugin(validConfig);
        plugin.start();
        plugin.stop();
    }
}
