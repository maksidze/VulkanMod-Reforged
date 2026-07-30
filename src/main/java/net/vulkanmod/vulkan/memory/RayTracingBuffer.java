package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/** A buffer whose allocation may be referenced by a Vulkan device address. */
public final class RayTracingBuffer extends Buffer {
    private final long deviceAddress;

    public RayTracingBuffer(int size, int usage, MemoryType memoryType) {
        super(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, memoryType);
        if (size <= 0) {
            throw new IllegalArgumentException("Ray tracing buffer size must be positive");
        }

        this.createBuffer(size);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack);
            addressInfo.sType$Default();
            addressInfo.buffer(this.id);
            this.deviceAddress = vkGetBufferDeviceAddress(Vulkan.getVkDevice(), addressInfo);
        }

        if (this.deviceAddress == 0L) {
            throw new IllegalStateException("Vulkan returned a null buffer device address");
        }
    }

    public void upload(ByteBuffer source) {
        upload(source, 0);
    }

    public void upload(ByteBuffer source, int destinationOffset) {
        if (!this.type.mappable()) {
            throw new IllegalStateException("Cannot directly upload to a non-mappable ray tracing buffer");
        }

        int size = source.remaining();
        if (destinationOffset < 0 || destinationOffset > this.bufferSize - size) {
            throw new IllegalArgumentException("Upload exceeds ray tracing buffer capacity");
        }

        MemoryUtil.memCopy(
                MemoryUtil.memAddress(source) + source.position(),
                this.data.get(0) + destinationOffset,
                size
        );
        this.offset = destinationOffset;
        this.usedBytes = Math.max(this.usedBytes, destinationOffset + size);
    }

    public long getDeviceAddress() {
        return this.deviceAddress;
    }
}
