package com.jewey.rosia.common.blocks.entity.block_entity;

import com.jewey.rosia.common.blocks.custom.electric_grill;
import com.jewey.rosia.common.blocks.entity.ModBlockEntities;
import com.jewey.rosia.common.container.ElectricGrillContainer;
import com.jewey.rosia.networking.ModMessages;
import com.jewey.rosia.networking.packet.EnergySyncS2CPacket;
import com.jewey.rosia.util.ModEnergyStorage;
import net.dries007.tfc.common.blockentities.TickableInventoryBlockEntity;
import net.dries007.tfc.common.capabilities.food.FoodCapability;
import net.dries007.tfc.common.capabilities.food.FoodTraits;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dries007.tfc.common.recipes.HeatingRecipe;
import net.dries007.tfc.common.recipes.inventory.ItemStackInventory;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.IntArrayBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Arrays;

import static com.jewey.rosia.Rosia.MOD_ID;

public class ElectricGrillBlockEntity extends TickableInventoryBlockEntity<ItemStackHandler> implements MenuProvider {

    public static final int SLOT_INPUT_MIN = 0;
    public static final int SLOT_INPUT_MAX = 9;

    private final ItemStackHandler itemHandler = new ItemStackHandler(10) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    public @NotNull Component getDisplayName() {
        return new TextComponent("Electric Grill");
    }
    private static final TranslatableComponent NAME = Helpers.translatable(MOD_ID + ".block_entity.electric_grill");

    public static void serverTick(Level level, BlockPos pos, BlockState state, ElectricGrillBlockEntity grill) {
        grill.checkForLastTickSync();

        if (grill.needsRecipeUpdate) {
            grill.needsRecipeUpdate = false;
        }

        // If item in slots -> turn on and consume power
        if(grill.ENERGY_STORAGE.getEnergyStored() >= ENERGY_REQ)
        {
            for (int slot = SLOT_INPUT_MIN; slot <= SLOT_INPUT_MAX; slot++)
            {
                final ItemStack inputStack = grill.inventory.getStackInSlot(slot);
                if (!inputStack.isEmpty()
                        && Helpers.mightHaveCapability(inputStack, HeatCapability.CAPABILITY))
                {
                    grill.temperature = 200;
                    level.setBlock(pos, state.setValue(electric_grill.ON, true), 3);
                    break;  // End the loop, so it doesn't find other empty slots and turn off
                }
                else
                {
                    turnOff(level, pos, state, grill);
                }
            }
        }
        else
        {
            turnOff(level, pos, state, grill);
        }
        if (grill.temperature > 0)
        {
            grill.ENERGY_STORAGE.extractEnergy(ENERGY_REQ, false);
        }

        grill.handleCooking();
    }

    private static void turnOff(Level level, BlockPos pos, BlockState state, ElectricGrillBlockEntity grill)
    {
        grill.temperature = 0;
        level.setBlock(pos, state.setValue(electric_grill.ON, false), 3);
    }

    protected void handleCooking()
    {
        for (int slot = SLOT_INPUT_MIN; slot <= SLOT_INPUT_MAX; slot++)
        {
            final ItemStack inputStack = inventory.getStackInSlot(slot);
            final int finalSlot = slot;
            inputStack.getCapability(HeatCapability.CAPABILITY, null).ifPresent(cap -> {
                HeatCapability.addTemp(cap, temperature);
                HeatingRecipe recipe = cachedRecipes[finalSlot - SLOT_INPUT_MIN];
                if (recipe != null && recipe.isValidTemperature(cap.getTemperature()))
                {
                    ItemStack output = recipe.assemble(new ItemStackInventory(inputStack));
                    FoodCapability.applyTrait(output, FoodTraits.WOOD_GRILLED);
                    inventory.setStackInSlot(finalSlot, output);
                    markForSync();
                }
            });
        }
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @org.jetbrains.annotations.Nullable Direction side) {
        if(cap == CapabilityEnergy.ENERGY) {
            return lazyEnergyHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    private final ModEnergyStorage ENERGY_STORAGE = new ModEnergyStorage(400, 10) {
        @Override
        public void onEnergyChanged() {
            setChanged();
            ModMessages.sendToClients(new EnergySyncS2CPacket(this.energy, getBlockPos()));
        }
    };

    private static final int ENERGY_REQ = 1;
    private LazyOptional<IEnergyStorage> lazyEnergyHandler = LazyOptional.empty();

    public IEnergyStorage getEnergyStorage() {
        return ENERGY_STORAGE;
    }

    public void setEnergyLevel(int energy) {
        this.ENERGY_STORAGE.setEnergy(energy);
    }

    protected final ContainerData syncableData;
    private final HeatingRecipe[] cachedRecipes;
    private boolean needsSlotUpdate = false;
    private float temperature; // Current Temperature
    private long lastPlayerTick; // Last player tick this forge was ticked (for purposes of catching up)
    private boolean needsRecipeUpdate; // Set to indicate on tick, the cached recipes need to be re-updated

    public ElectricGrillBlockEntity(BlockPos pos, BlockState state)
    {
        super(ModBlockEntities.ELECTRIC_GRILL_BLOCK_ENTITY.get(), pos, state, defaultInventory(10), NAME);

        cachedRecipes = new HeatingRecipe[10];

        temperature = 0;
        lastPlayerTick = Integer.MIN_VALUE;
        syncableData = new IntArrayBuilder().add(() -> (int) temperature, value -> temperature = value);

        Arrays.fill(cachedRecipes, null);
    }

    @Deprecated
    public long getLastUpdateTick()
    {
        return lastPlayerTick;
    }

    @Deprecated
    public void setLastUpdateTick(long tick)
    {
        lastPlayerTick = tick;
    }

    public ContainerData getSyncableData()
    {
        return syncableData;
    }

    public float getTemperature()
    {
        return temperature;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowID, Inventory playerInv, Player player)
    {
        ModMessages.sendToClients(new EnergySyncS2CPacket(this.ENERGY_STORAGE.getEnergyStored(), getBlockPos()));
        return ElectricGrillContainer.create(this, playerInv, windowID);
    }

    @Override
    public void onLoad() {
        lazyEnergyHandler = LazyOptional.of(() -> ENERGY_STORAGE);
    }

    @Override
    public void invalidateCaps()  {
        super.invalidateCaps();
        lazyEnergyHandler.invalidate();
    }

    @Override
    public void loadAdditional(CompoundTag nbt)
    {
        temperature = nbt.getFloat("temperature");
        lastPlayerTick = nbt.getLong("lastPlayerTick");
        ENERGY_STORAGE.setEnergy(nbt.getInt("energy"));
        super.loadAdditional(nbt);
    }

    @Override
    public void saveAdditional(CompoundTag nbt)
    {
        nbt.putFloat("temperature", temperature);
        nbt.putLong("lastPlayerTick", lastPlayerTick);
        nbt.putInt("energy", ENERGY_STORAGE.getEnergyStored());
        super.saveAdditional(nbt);
    }

    public void drops() {
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            inventory.setItem(i, itemHandler.getStackInSlot(i));
        }

        Containers.dropContents(this.level, this.worldPosition, inventory);
    }

    @Override
    public void setAndUpdateSlots(int slot)
    {
        super.setAndUpdateSlots(slot);
        needsSlotUpdate = true;
        updateCachedRecipes();
    }

    @Override
    public int getSlotStackLimit(int slot)
    {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        return Helpers.mightHaveCapability(stack, HeatCapability.CAPABILITY) && Helpers.mightHaveCapability(stack, FoodCapability.CAPABILITY);
    }

    private void updateCachedRecipes()
    {
        // cache heat recipes for each input
        assert level != null;
        for (int i = SLOT_INPUT_MIN; i <= SLOT_INPUT_MAX; i++)
        {
            cachedRecipes[i - SLOT_INPUT_MIN] = null;
            ItemStack inputStack = inventory.getStackInSlot(i);
            if (!inputStack.isEmpty())
            {
                cachedRecipes[i - SLOT_INPUT_MIN] = HeatingRecipe.getRecipe(new ItemStackInventory(inputStack));
            }
        }
    }
}
