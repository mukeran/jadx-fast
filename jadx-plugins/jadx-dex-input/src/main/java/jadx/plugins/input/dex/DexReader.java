package jadx.plugins.input.dex;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import jadx.api.plugins.input.data.IClassData;
import jadx.plugins.input.dex.sections.DexClassData;
import jadx.plugins.input.dex.sections.DexHeader;
import jadx.plugins.input.dex.sections.SectionReader;
import jadx.plugins.input.dex.sections.annotations.AnnotationsParser;

public class DexReader {
	private final int uniqId;
	private final String inputFileName;
	private final ByteBuffer buf;
	private final DexHeader header;
	private volatile Map<String, Integer> classDefIndex;

	public DexReader(int uniqId, String inputFileName, byte[] content, int offset) {
		this.uniqId = uniqId;
		this.inputFileName = inputFileName;
		this.buf = ByteBuffer.wrap(content);
		this.header = new DexHeader(new SectionReader(this, offset));
	}

	public void visitClasses(Consumer<IClassData> consumer) {
		int count = header.getClassDefsSize();
		if (count == 0) {
			return;
		}
		int classDefsOff = header.getClassDefsOff();
		SectionReader in = new SectionReader(this, classDefsOff);
		AnnotationsParser annotationsParser = new AnnotationsParser(in.copy(), in.copy());
		DexClassData classData = new DexClassData(in, annotationsParser);
		for (int i = 0; i < count; i++) {
			consumer.accept(classData);
			in.shiftOffset(DexClassData.SIZE);
		}
	}

	public boolean visitClass(String clsName, Consumer<IClassData> consumer) {
		Integer classDefOffset = getClassDefIndex().get(cleanClassName(clsName));
		if (classDefOffset == null) {
			return false;
		}
		SectionReader in = new SectionReader(this, classDefOffset);
		AnnotationsParser annotationsParser = new AnnotationsParser(in.copy(), in.copy());
		consumer.accept(new DexClassData(in, annotationsParser));
		return true;
	}

	private Map<String, Integer> getClassDefIndex() {
		Map<String, Integer> index = classDefIndex;
		if (index == null) {
			synchronized (this) {
				index = classDefIndex;
				if (index == null) {
					index = buildClassDefIndex();
					classDefIndex = index;
				}
			}
		}
		return index;
	}

	void prepareClassDefIndex() {
		getClassDefIndex();
	}

	private Map<String, Integer> buildClassDefIndex() {
		int count = header.getClassDefsSize();
		Map<String, Integer> index = new HashMap<>(count);
		int classDefsOff = header.getClassDefsOff();
		SectionReader in = new SectionReader(this, classDefsOff);
		AnnotationsParser annotationsParser = new AnnotationsParser(in.copy(), in.copy());
		DexClassData classData = new DexClassData(in, annotationsParser);
		for (int i = 0; i < count; i++) {
			index.put(cleanClassName(classData.getType()), classDefsOff + i * DexClassData.SIZE);
			in.shiftOffset(DexClassData.SIZE);
		}
		return index;
	}

	private static String cleanClassName(String clsName) {
		if (!clsName.isEmpty() && clsName.charAt(0) == 'L' && clsName.charAt(clsName.length() - 1) == ';') {
			return clsName.substring(1, clsName.length() - 1).replace('/', '.');
		}
		return clsName;
	}

	public ByteBuffer getBuf() {
		return buf;
	}

	public DexHeader getHeader() {
		return header;
	}

	public String getInputFileName() {
		return inputFileName;
	}

	public int getUniqId() {
		return uniqId;
	}

	@Override
	public String toString() {
		return inputFileName;
	}
}
