package jadx.core.clsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.Consts;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.exceptions.DecodeException;
import jadx.core.utils.exceptions.JadxRuntimeException;

/**
 * Classes hierarchy graph with methods additional info
 */
public class ClspGraph {
	private static final Logger LOG = LoggerFactory.getLogger(ClspGraph.class);

	private final RootNode root;
	private Map<String, ClspClass> nameMap;
	private Map<String, Set<String>> superTypesCache;
	private Map<String, List<String>> implementsCache;

	private final Set<String> missingClasses = new HashSet<>();

	public ClspGraph(RootNode rootNode) {
		this.root = rootNode;
	}

	public static ClspGraph loadBase(RootNode root) throws IOException, DecodeException {
		ClspGraph graph = new ClspGraph(root);
		graph.loadClsSetFile();
		graph.initCache();
		return graph;
	}

	public ClspGraph copyWithApp(RootNode newRoot, List<ClassNode> classes) {
		ClspGraph copy = new ClspGraph(newRoot);
		copy.nameMap = new HashMap<>(nameMap);
		copy.superTypesCache = new HashMap<>(superTypesCache);
		copy.implementsCache = new HashMap<>(implementsCache);
		copy.addApp(classes);
		copy.addAppToCache(classes);
		return copy;
	}

	public void loadClsSetFile() throws IOException, DecodeException {
		ClsSet set = new ClsSet(root);
		set.loadFromClstFile();
		addClasspath(set);
	}

	public void addClasspath(ClsSet set) {
		if (nameMap == null) {
			nameMap = new HashMap<>(set.getClassesCount());
			set.addToMap(nameMap);
		} else {
			throw new JadxRuntimeException("Classpath already loaded");
		}
	}

	public void addApp(List<ClassNode> classes) {
		if (nameMap == null) {
			nameMap = new HashMap<>(classes.size());
		}
		for (ClassNode cls : classes) {
			addClass(cls);
		}
	}

	public void initCache() {
		fillSuperTypesCache();
		fillImplementsCache();
	}

	public boolean isClsKnown(String fullName) {
		return nameMap.containsKey(fullName);
	}

	public ClspClass getClsDetails(ArgType type) {
		return nameMap.get(type.getObject());
	}

	@Nullable
	public IMethodDetails getMethodDetails(MethodInfo methodInfo) {
		ClspClass cls = nameMap.get(methodInfo.getDeclClass().getRawName());
		if (cls == null) {
			return null;
		}
		ClspMethod clspMethod = getMethodFromClass(cls, methodInfo);
		if (clspMethod != null) {
			return clspMethod;
		}
		// deep search
		for (ArgType parent : cls.getParents()) {
			ClspClass clspParent = getClspClass(parent);
			if (clspParent != null) {
				ClspMethod methodFromParent = getMethodFromClass(clspParent, methodInfo);
				if (methodFromParent != null) {
					return methodFromParent;
				}
			}
		}
		// unknown method
		return new SimpleMethodDetails(methodInfo);
	}

	private ClspMethod getMethodFromClass(ClspClass cls, MethodInfo methodInfo) {
		return cls.getMethodsMap().get(methodInfo.getShortId());
	}

	private void addClass(ClassNode cls) {
		ArgType clsType = cls.getClassInfo().getType();
		String rawName = clsType.getObject();
		ClspClass clspClass = new ClspClass(clsType, -1, cls.getAccessFlags().rawValue(), ClspClassSource.APP);
		clspClass.setParents(ClsSet.makeParentsArray(cls));
		nameMap.put(rawName, clspClass);
	}

	/**
	 * @return {@code clsName} instanceof {@code implClsName}
	 */
	public boolean isImplements(String clsName, String implClsName) {
		Set<String> anc = getSuperTypes(clsName);
		return anc.contains(implClsName);
	}

	public List<String> getImplementations(String clsName) {
		List<String> list = implementsCache.get(clsName);
		return list == null ? Collections.emptyList() : list;
	}

	private void fillImplementsCache() {
		Map<String, List<String>> map = new HashMap<>(nameMap.size());
		List<String> classes = new ArrayList<>(nameMap.keySet());
		Collections.sort(classes);
		for (String cls : classes) {
			for (String st : getSuperTypes(cls)) {
				map.computeIfAbsent(st, v -> new ArrayList<>()).add(cls);
			}
		}
		implementsCache = map;
	}

	public String getCommonAncestor(String clsName, String implClsName) {
		if (clsName.equals(implClsName)) {
			return clsName;
		}
		ClspClass cls = nameMap.get(implClsName);
		if (cls == null) {
			missingClasses.add(clsName);
			return null;
		}
		if (isImplements(clsName, implClsName)) {
			return implClsName;
		}
		Set<String> anc = getSuperTypes(clsName);
		return searchCommonParent(anc, cls);
	}

	private String searchCommonParent(Set<String> anc, ClspClass cls) {
		for (ArgType p : cls.getParents()) {
			String name = p.getObject();
			if (anc.contains(name)) {
				return name;
			}
			ClspClass nCls = getClspClass(p);
			if (nCls != null) {
				String r = searchCommonParent(anc, nCls);
				if (r != null) {
					return r;
				}
			}
		}
		return null;
	}

	public Set<String> getSuperTypes(String clsName) {
		Set<String> result = superTypesCache.get(clsName);
		return result == null ? Collections.emptySet() : result;
	}

	private static final Set<String> OBJECT_SINGLE_SET = Collections.singleton(Consts.CLASS_OBJECT);

	private void fillSuperTypesCache() {
		Map<String, Set<String>> map = new HashMap<>(nameMap.size());
		for (Map.Entry<String, ClspClass> entry : nameMap.entrySet()) {
			map.put(entry.getKey(), collectSuperTypes(entry.getValue()));
		}
		superTypesCache = map;
	}

	private void addAppToCache(List<ClassNode> classes) {
		for (ClassNode cls : classes) {
			String clsName = cls.getRawName();
			ClspClass clspClass = nameMap.get(clsName);
			Set<String> superTypes = collectSuperTypes(clspClass);
			superTypesCache.put(clsName, superTypes);
			for (String superType : superTypes) {
				List<String> baseList = implementsCache.get(superType);
				List<String> updatedList = baseList == null ? new ArrayList<>() : new ArrayList<>(baseList);
				updatedList.add(clsName);
				implementsCache.put(superType, updatedList);
			}
		}
	}

	private Set<String> collectSuperTypes(ClspClass cls) {
		Set<String> result = new HashSet<>();
		addSuperTypes(cls, result);
		int size = result.size();
		if (size == 0) {
			return Collections.emptySet();
		}
		if (size == 1) {
			String supCls = result.iterator().next();
			if (supCls.equals(Consts.CLASS_OBJECT)) {
				return OBJECT_SINGLE_SET;
			}
			return Collections.singleton(supCls);
		}
		return result;
	}

	private void addSuperTypes(ClspClass cls, Set<String> result) {
		for (ArgType parentType : cls.getParents()) {
			if (parentType == null) {
				continue;
			}
			ClspClass parentCls = getClspClass(parentType);
			if (parentCls != null) {
				boolean isNew = result.add(parentCls.getName());
				if (isNew) {
					addSuperTypes(parentCls, result);
				}
			} else {
				// parent type is unknown
				result.add(parentType.getObject());
			}
		}
	}

	@Nullable
	private ClspClass getClspClass(ArgType clsType) {
		ClspClass clspClass = nameMap.get(clsType.getObject());
		if (clspClass == null) {
			missingClasses.add(clsType.getObject());
		}
		return clspClass;
	}

	public void printMissingClasses() {
		int count = missingClasses.size();
		if (count == 0) {
			return;
		}
		LOG.warn("Found {} references to unknown classes", count);
		if (LOG.isDebugEnabled()) {
			List<String> clsNames = new ArrayList<>(missingClasses);
			Collections.sort(clsNames);
			for (String cls : clsNames) {
				LOG.debug("  {}", cls);
			}
		}
	}
}
