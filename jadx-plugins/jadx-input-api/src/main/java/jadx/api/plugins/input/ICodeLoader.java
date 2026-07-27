package jadx.api.plugins.input;

import java.io.Closeable;
import java.util.function.Consumer;

import jadx.api.plugins.input.data.IClassData;

public interface ICodeLoader extends Closeable {

	void visitClasses(Consumer<IClassData> consumer);

	default boolean visitClass(String clsName, Consumer<IClassData> consumer) {
		boolean[] found = { false };
		visitClasses(cls -> {
			if (cls.getType().equals(clsName)) {
				consumer.accept(cls);
				found[0] = true;
			}
		});
		return found[0];
	}

	default void prepareSingleClassLookup() {
	}

	boolean isEmpty();
}
