package io.github.azotorp.wowuisync.server;
import io.github.azotorp.wowuisync.server.security.StoreLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class StoreLeaseTest {
    @TempDir Path root;
    @Test void secondServerRefusedAndLeaseReleased() throws Exception {
        try(var a=StoreLease.acquire(root)){assertThrows(java.io.IOException.class,()->StoreLease.acquire(root));}
        try(var again=StoreLease.acquire(root)){assertNotNull(again);}
    }
}
