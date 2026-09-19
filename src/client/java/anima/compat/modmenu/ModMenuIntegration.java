package anima.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.Minecraft;

import anima.client.gui.CompositeEditScreen;

/**
 * Optional Mod Menu integration (Fabric only). Mod Menu is a non-required dependency:
 * this entrypoint is only loaded when Mod Menu is present, so the library runs fine without it.
 * <p>
 * Opens the library's timeline editor (floating window while inside a world, plain panel otherwise).
 * Editors that want the dedicated preview world must ask for it themselves
 * (see {@code AnimaApi.isConfigWorld()} / {@code AnimaApi.enterConfigWorld}).
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> Minecraft.getInstance().level != null
			? new CompositeEditScreen(parent, true)
			: new CompositeEditScreen(parent);
	}
}
