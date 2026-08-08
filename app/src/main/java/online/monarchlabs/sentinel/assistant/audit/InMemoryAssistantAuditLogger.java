package online.monarchlabs.sentinel.assistant.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InMemoryAssistantAuditLogger implements AssistantAuditLogger {
    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void log(AuditEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    public List<AuditEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
