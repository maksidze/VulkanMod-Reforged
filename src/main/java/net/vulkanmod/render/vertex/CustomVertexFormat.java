package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class CustomVertexFormat {

    public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, 0,VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(1, 0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
    public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(2, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2);
    public static final VertexFormatElement ELEMENT_NORMAL = new VertexFormatElement(3, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 4);
    public static final VertexFormatElement ELEMENT_EXTERNAL_LOD_POSITION = new VertexFormatElement(0, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_EXTERNAL_LOD_PADDING = new VertexFormatElement(2, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 4);

    public static final VertexFormat COMPRESSED_TERRAIN = VertexFormat.builder()
            .add("Position", ELEMENT_POSITION)
            .add("Color", ELEMENT_COLOR)
            .add("UV0", ELEMENT_UV0)
            .add("Normal", ELEMENT_NORMAL)
            .build();

    public static final VertexFormat EXTERNAL_LOD = VertexFormat.builder()
            .add("Position", ELEMENT_EXTERNAL_LOD_POSITION)
            .add("Color", ELEMENT_COLOR)
            .add("Padding", ELEMENT_EXTERNAL_LOD_PADDING)
            .build();

    public static final VertexFormat NONE = VertexFormat.builder().build();
}
