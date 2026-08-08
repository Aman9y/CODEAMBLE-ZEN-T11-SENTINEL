package online.monarchlabs.sentinel.assistant.providers;

public abstract class AssistantCard {
    public final String type;

    protected AssistantCard(String type) {
        this.type = type;
    }
}
