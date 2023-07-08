package com.jewey.rosia.common.blocks.entity.block_entity;

import com.jewey.rosia.common.blocks.custom.nickel_iron_battery;
import com.jewey.rosia.common.blocks.entity.ModBlockEntities;
import com.jewey.rosia.common.blocks.entity.WrappedHandlerEnergy;
import com.jewey.rosia.common.container.ElectricForgeContainer;
import com.jewey.rosia.networking.ModMessages;
import com.jewey.rosia.networking.packet.EnergySyncS2CPacket;
import com.jewey.rosia.screen.NickelIronBatteryContainer;
import com.jewey.rosia.util.ModEnergyStorage;
import net.dries007.tfc.common.blockentities.TickableInventoryBlockEntity;
import net.dries007.tfc.util.Helpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.jewey.rosia.Rosia.MOD_ID;


public class NickelIronBatteryBlockEntity extends TickableInventoryBlockEntity<ItemStackHandler> implements MenuProvider {

    private final ItemStackHandler itemHandler = new ItemStackHandler(0) {};
    protected final ContainerData data;


    public NickelIronBatteryBlockEntity(BlockPos pPos, BlockState pBlockState) {
        super(ModBlockEntities.NICKEL_IRON_BATTERY_BLOCK_ENTITY.get(), pPos, pBlockState, defaultInventory(0), NAME);
        this.data = new ContainerData() {
            public int get(int index) {
                return switch (index) {
                    default -> 0;
                };
            }
            public void set(int index, int value) {
                switch (index) {
                }
            }
            public int getCount() {
                return 2;
            }
        };
    }

    @Override
    public @NotNull Component getDisplayName() {
        return new TextComponent("Battery");
    }

    private static final TranslatableComponent NAME = Helpers.translatable(MOD_ID + ".block_entity.nickel_iron_battery");

    public ContainerData getSyncableData()
    {
        return data;
    }

    @javax.annotation.Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowID, Inventory playerInv, Player player)
    {
        ModMessages.sendToClients(new EnergySyncS2CPacket(this.ENERGY_STORAGE.getEnergyStored(), getBlockPos()));
        return NickelIronBatteryContainer.create(this, playerInv, windowID);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if(cap == CapabilityEnergy.ENERGY) {
            if(side == null) {
                return lazyEnergyHandler.cast();
            }

            if(directionWrappedHandlerMap.containsKey(side)) {
                Direction localDir = this.getBlockState().getValue(nickel_iron_battery.FACING);

                if(side == Direction.UP || side == Direction.DOWN) {
                    return directionWrappedHandlerMap.get(side).cast();
                }

                return switch (localDir) {
                    default -> directionWrappedHandlerMap.get(side.getOpposite()).cast();
                    case EAST -> directionWrappedHandlerMap.get(side.getClockWise()).cast();
                    case SOUTH -> directionWrappedHandlerMap.get(side).cast();
                    case WEST -> directionWrappedHandlerMap.get(side.getCounterClockWise()).cast();
                };
            }
        }
        return super.getCapability(cap, side);
    }

    private final ModEnergyStorage ENERGY_STORAGE = new ModEnergyStorage(4000, maxTransfer) {
        @Override
        public void onEnergyChanged() {
            setChanged();
            assert level != null;
            ModMessages.sendToClients(new EnergySyncS2CPacket(this.energy, getBlockPos()));
        }
    };

    private final Map<Direction, LazyOptional<WrappedHandlerEnergy>> directionWrappedHandlerMap =
            //Handler for sided energy: extract, receive, canExtract, canReceive
            //Determines what sides can perform actions
            Map.of(Direction.DOWN, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE, (i) -> false, (i) -> true, false, true)),
                    Direction.UP, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE,(i) -> true, (i) -> false, true, false)),
                    Direction.NORTH, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE, (i) -> false, (i) -> true, false, true)),
                    Direction.SOUTH, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE, (i) -> false, (i) -> true, false, true)),
                    Direction.EAST, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE, (i) -> false, (i) -> true, false, true)),
                    Direction.WEST, LazyOptional.of(() -> new WrappedHandlerEnergy(ENERGY_STORAGE, (i) -> false, (i) -> true, false, true)));

    private static final int maxTransfer = 50;

    private LazyOptional<IEnergyStorage> lazyEnergyHandler = LazyOptional.empty();

    public IEnergyStorage getEnergyStorage() {
        return ENERGY_STORAGE;
    }

    public void setEnergyLevel(int energy) {
        this.ENERGY_STORAGE.setEnergy(energy);
    }

    public void outputEnergy() {
        if (this.ENERGY_STORAGE.getEnergyStored() > 0 && this.ENERGY_STORAGE.canExtract() && toggle) {
            final var direction = Direction.UP;
            final BlockEntity neighbor = this.level.getBlockEntity(this.worldPosition.relative(direction));

            if (neighbor != null) {
                neighbor.getCapability(CapabilityEnergy.ENERGY, direction.getOpposite()).ifPresent(storage -> {
                    if (neighbor != this && storage.canReceive() && storage.getEnergyStored() < storage.getMaxEnergyStored()) {
                        final int canReceive = Math.min(storage.getMaxEnergyStored() - storage.getEnergyStored(), maxTransfer);
                        final int toSend = NickelIronBatteryBlockEntity.this.ENERGY_STORAGE.extractEnergy(canReceive,false);
                        final int received = storage.receiveEnergy(toSend, false);

                        NickelIronBatteryBlockEntity.this.ENERGY_STORAGE.setEnergy(NickelIronBatteryBlockEntity.this.ENERGY_STORAGE.getEnergyStored() + toSend - received);
                    }
                });
            }
        }
    }


    public static void tick(Level pLevel, BlockPos pPos, BlockState pState, NickelIronBatteryBlockEntity battery) {
        //output energy on block side UP
        battery.outputEnergy();
        battery.setChanged();
    }

    private boolean toggle = false;

    public InteractionResult togglePush(){
        if(!toggle) {
            toggle = true;
        } else {
            toggle = false;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        lazyEnergyHandler = LazyOptional.of(() -> ENERGY_STORAGE);
    }

    @Override
    public void invalidateCaps()  {
        super.invalidateCaps();
        lazyEnergyHandler.invalidate();
    }

    @Override
    public void loadAdditional(CompoundTag nbt) {
        ENERGY_STORAGE.setEnergy(nbt.getInt("energy"));
        toggle = nbt.getBoolean("toggle");
        super.loadAdditional(nbt);
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        tag.putInt("energy", ENERGY_STORAGE.getEnergyStored());
        tag.putBoolean("toggle", toggle);
        super.saveAdditional(tag);
    }

    public void drops() {
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            inventory.setItem(i, itemHandler.getStackInSlot(i));
        }

        Containers.dropContents(this.level, this.worldPosition, inventory);
    }
}
