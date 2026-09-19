package anima.manager;

/** The kind of animation an {@link AnimationDefinition} describes. */
public enum AnimationType {
	GUI_SPRITE("gui_sprite"),
	GUI_DYNAMIC("gui_dynamic"),
	WORLD_SPRITE("world_sprite"),
	TEXT("text");

	private final String jsonName;

	AnimationType(String jsonName) {
		this.jsonName = jsonName;
	}

	public static AnimationType fromString(String value) {
		if (value == null) {
			return GUI_SPRITE;
		}
		return switch (value.toLowerCase()) {
			case "gui_dynamic" -> GUI_DYNAMIC;
			case "world_sprite" -> WORLD_SPRITE;
			case "text" -> TEXT;
			default -> GUI_SPRITE;
		};
	}
}
