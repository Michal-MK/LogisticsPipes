/**
 * "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package logisticspipes.pipes;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

import logisticspipes.blocks.LogisticsProgramCompilerTileEntity;
import logisticspipes.blocks.LogisticsSecurityTileEntity;
import logisticspipes.blocks.powertile.LogisticsPowerJunctionTileEntity;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.modules.LogisticsModule;
import logisticspipes.modules.LogisticsModule.ModulePositionType;
import logisticspipes.modules.ModuleItemSink;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.routing.pathfinder.IPipeInformationProvider.ConnectionPipeType;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.transport.PipeTransportLogistics;
import logisticspipes.utils.OrientationsUtil;
import logisticspipes.utils.item.ItemIdentifier;
import network.rs485.logisticspipes.connection.NeighborTileEntity;
import network.rs485.logisticspipes.world.WorldCoordinatesWrapper;

public class PipeItemsBasicLogistics extends CoreRoutedPipe {

	private ModuleItemSink itemSinkModule;

	public PipeItemsBasicLogistics(Item item) {
		super(new PipeTransportLogistics(true) {

			@Override
			public boolean canPipeConnect(TileEntity tile, EnumFacing dir) {
				if (super.canPipeConnect(tile, dir)) {
					return true;
				}
				if (tile instanceof LogisticsSecurityTileEntity) {
					EnumFacing ori = OrientationsUtil.getOrientationOfTilewithTile(container, tile);
					if (ori == null || ori == EnumFacing.DOWN || ori == EnumFacing.UP) {
						return false;
					}
					return true;
				}
				if (tile instanceof LogisticsProgramCompilerTileEntity) {
					EnumFacing ori = OrientationsUtil.getOrientationOfTilewithTile(container, tile);
					if (ori == null || ori == EnumFacing.DOWN) {
						return false;
					}
					return true;
				}
				return false;
			}
		}, item);
		itemSinkModule = new ModuleItemSink();
		itemSinkModule.registerHandler(this, this);
	}

	@Override
	public TextureType getNonRoutedTexture(EnumFacing connection) {
		if (isSecurityProvider(connection)) {
			return Textures.LOGISTICSPIPE_SECURITY_TEXTURE;
		}
		return super.getNonRoutedTexture(connection);
	}

	@Override
	public boolean isLockedExit(EnumFacing orientation) {
		if (isPowerJunction(orientation) || isSecurityProvider(orientation)) {
			return true;
		}
		return super.isLockedExit(orientation);
	}

	private boolean isPowerJunction(EnumFacing ori) {
		TileEntity tilePipe = container.getTile(ori);
		if (tilePipe == null || !container.canPipeConnect(tilePipe, ori)) {
			return false;
		}

		if (tilePipe instanceof LogisticsPowerJunctionTileEntity) {
			return true;
		}
		return false;
	}

	private boolean isSecurityProvider(EnumFacing ori) {
		TileEntity tilePipe = container.getTile(ori);
		if (tilePipe == null || !container.canPipeConnect(tilePipe, ori)) {
			return false;
		}
		if (tilePipe instanceof LogisticsSecurityTileEntity) {
			return true;
		}
		return false;
	}

	@Override
	public TextureType getCenterTexture() {
		return Textures.LOGISTICSPIPE_TEXTURE;
	}

	@Override
	public LogisticsModule getLogisticsModule() {
		return itemSinkModule;
	}

	@Override
	public ItemSendMode getItemSendMode() {
		return ItemSendMode.Normal;
	}

	@Override
	public void setTile(TileEntity tile) {
		super.setTile(tile);
		itemSinkModule.registerPosition(ModulePositionType.IN_PIPE, 0);
	}

	@Nullable
	@Override
	public IInventoryUtil getPointedInventory() {
		IInventoryUtil invUtil = super.getPointedInventory();
		if (invUtil == null) {
			invUtil = new WorldCoordinatesWrapper(container)
					.connectedTileEntities(ConnectionPipeType.ITEM)
					.filter(NeighborTileEntity::isItemHandler)
					.findFirst()
					.map(NeighborTileEntity::getUtilForItemHandler)
					.orElse(null);
		}
		return invUtil;
	}

	@Override
	public void collectSpecificInterests(@Nonnull Collection<ItemIdentifier> itemidCollection) {
		if (!itemSinkModule.isDefaultRoute()) {
			itemSinkModule.collectSpecificInterests(itemidCollection);
		}
	}

	@Override
	public boolean hasGenericInterests() {
		return itemSinkModule.isDefaultRoute();
	}
}
