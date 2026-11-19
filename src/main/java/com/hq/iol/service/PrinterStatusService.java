package com.hq.iol.service;

import com.hq.iol.device.printer.PrinterProbe;
import com.hq.iol.device.printer.PrinterProfile;
import com.hq.iol.device.printer.TscPrinterDriver;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 周期探测全部打印机，配置变更时立即刷新，探测不占用 JavaFX 线程。 */
@Service
public class PrinterStatusService {

    private final PrinterRegistry registry;
    private final Vertx vertx;
    private final CopyOnWriteArrayList<Consumer<Map<String, PrinterProbe>>> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean probing = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "printer-status-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Map<String, PrinterProbe> snapshot = Map.of();

    public PrinterStatusService(PrinterRegistry registry, Vertx vertx) {
        this.registry = registry;
        this.vertx = vertx;
        registry.addListener(() -> {
            if (running.get()) {
                scheduler.execute(this::refreshNow);
            }
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.execute(this::refreshNow);
            scheduler.scheduleWithFixedDelay(this::refreshNow, 5, 5, TimeUnit.SECONDS);
        }
    }

    public Map<String, PrinterProbe> refreshNow() {
        if (!probing.compareAndSet(false, true)) {
            return snapshot;
        }
        try {
            Map<String, PrinterProbe> current = new LinkedHashMap<>();
            for (PrinterProfile profile : registry.list()) {
                PrinterProbe probe;
                try {
                    probe = new TscPrinterDriver(vertx, profile.host(), profile.port(), 2000).probe();
                } catch (RuntimeException e) {
                    probe = new PrinterProbe(false, null);
                }
                current.put(profile.id(), probe);
            }
            snapshot = Map.copyOf(current);
            listeners.forEach(listener -> listener.accept(snapshot));
            return snapshot;
        } finally {
            probing.set(false);
        }
    }

    public void addListener(Consumer<Map<String, PrinterProbe>> listener) {
        listeners.add(listener);
    }

    public List<PrinterProfile> profiles() {
        return registry.list();
    }

    public Map<String, PrinterProbe> snapshot() {
        return snapshot;
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
