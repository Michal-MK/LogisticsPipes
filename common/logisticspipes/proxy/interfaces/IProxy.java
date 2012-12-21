package logisticspipes.proxy.interfaces;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public interface IProxy {
	public String getSide();
	public World getWorld();
	public void registerTileEntitis();
	public World getWorld(int _dimension);
	public EntityPlayer getClientPlayer();
	public boolean isMainThreadRunning();
	public void addLogisticsPipesOverride(int index, String override1, String override2);
	public void registerParticles();
}