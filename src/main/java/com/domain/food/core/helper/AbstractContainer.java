package com.domain.food.core.helper;

import com.domain.food.config.ConfigProperties;
import com.domain.food.consts.Constant;
import com.domain.food.utils.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 抽象容器
 *
 * @param <K> 主键类型
 * @param <E> 实体类型
 * @author zhoutaotao
 * @date 2019/5/16
 */
public abstract class AbstractContainer<K, E> extends EntityBeanAnalyser<K, E>
        implements IDaoClear, ApplicationContextAware, DisposableBean {

    private ConfigProperties properties;

    // 任务调度
    protected ScheduledExecutorService executor = null;

    // 数据持久化缓存
    private transient volatile Map<K, EntityWrap<E>> entityCacheMap = new ConcurrentHashMap<>();

    // 实体操作类型分组
    private transient volatile Map<EntityWrap.Type, List<E>> entityGroupMap = new ConcurrentHashMap<>();

    // 所有查询过的参数缓存
    private transient volatile Set<Map<String, Object>> queryParamSet = Collections.synchronizedSet(new HashSet<>());

    // id与查询参数的对应关系
    private transient volatile Map<K, List<Map<String, Object>>> keyToQueryParamMap = new ConcurrentHashMap<>();

    /**
     * 添加到查询参数缓存
     *
     * @param map 查询参数
     * @return true:插入成功，第一次使用该参数查询
     */
    private boolean addToQueryParamSet(Map<String, Object> map) {
        if (queryParamSet.contains(map)) {
            return false;
        }
        queryParamSet.add(map);
        return true;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();
        // 初始化分组集合
        initEntityGroupMap();

        // 启动定时器，定时刷库
        executor = Executors.newScheduledThreadPool(1);

        int interval = properties.getDb().getInterval();
        if (log.isDebugEnabled()) {
            log.debug("启动定时器, 循环时间：{}分/次", interval);
        }

        executor.scheduleWithFixedDelay(() -> {
            try {
                writeDataToDisk();
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }, interval, interval, TimeUnit.MINUTES);
    }

    /**
     * 初始化分组集合
     */
    private synchronized void initEntityGroupMap() {
        if (log.isDebugEnabled()) {
            log.debug("正在初始化分组集合");
        }
        entityGroupMap.put(EntityWrap.Type.DEFAULT, Collections.synchronizedList(new ArrayList<>()));
        entityGroupMap.put(EntityWrap.Type.ADD, Collections.synchronizedList(new ArrayList<>()));
        entityGroupMap.put(EntityWrap.Type.UPDATE, Collections.synchronizedList(new ArrayList<>()));
        entityGroupMap.put(EntityWrap.Type.DELETE, Collections.synchronizedList(new ArrayList<>()));
    }

    /**
     * 重置分组集合
     */
    private synchronized void resetEntityGroupMap() {
        if (log.isDebugEnabled()) {
            log.debug("重置分组集合");
        }
        entityGroupMap.get(EntityWrap.Type.DEFAULT).clear();
        entityGroupMap.get(EntityWrap.Type.ADD).clear();
        entityGroupMap.get(EntityWrap.Type.UPDATE).clear();
        entityGroupMap.get(EntityWrap.Type.DELETE).clear();
    }

    /**
     * 从磁盘加载数据
     *
     * @param map 查询参数
     */
    protected void loadData(Map<String, Object> map) {
        // 拷贝 map 中的键值对
        Map<String, Object> tmpMap = CollectionUtil.copy(map);

        if (log.isDebugEnabled()) {
            log.debug("开始从磁盘加载数据：{}", map);
        }

        // 已经使用过该参数查询
        if (!addToQueryParamSet(tmpMap)) {
            if (log.isDebugEnabled()) {
                log.debug("从磁盘加载数据被禁止：{}", map);
            }
            // 查询时自动将缓存时间延长
            resetExpireTime(tmpMap);
            return;
        }

        // 声明锁对象
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            // 真正的从磁盘加载数据
            loadDataFromDisk(tmpMap);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 延长缓存时间
     *
     * @param map 查询键值对
     */
    private void resetExpireTime(Map<String, Object> map) {
        long time = System.currentTimeMillis();
        for (K id : keyToQueryParamMap.keySet()) {
            List<Map<String, Object>> mapList = keyToQueryParamMap.get(id);
            if (mapList.contains(map)) {
                EntityWrap<E> entityWrap = getEntityCacheMap().get(id);
                if (entityWrap == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("延长缓存时间,缓存不存在:{}", map);
                    }
                    keyToQueryParamMap.remove(id);
                    queryParamSet.remove(map);
                    loadData(map);
                    return;
                }
                if (entityWrap.getExpireTime() <= time) {
                    if (log.isDebugEnabled()) {
                        log.debug("延长缓存时间,缓存已过期:{}", map);
                    }
                    keyToQueryParamMap.remove(id);
                    queryParamSet.remove(map);
                    loadData(map);
                    return;
                }
                // 延长缓存时间
                if (log.isDebugEnabled()) {
                    log.debug("延长缓存时间,缓存:{}, map:{}", entityWrap, map);
                }
                entityWrap.setExpireTime(getExpireTime());
            }
        }
    }

    /**
     * 从磁盘加载数据, 方法体内并为对数据进行同步操作
     * 使用方式
     * <code>
     * // 声明锁对象
     * Lock lock = new ReentrantLock();
     * try {
     * lock.lock();
     * // 真正的从磁盘加载数据
     * loadDataFromDisk(tmpMap);
     * } finally {
     * lock.unlock();
     * }
     * </code>
     */
    private void loadDataFromDisk(Map<String, Object> map) {

        // 加载数据
        String filePathAtDisk = getDiskFilePath();

        if (log.isDebugEnabled()) {
            log.debug("获取文件数据, [path : {}]", filePathAtDisk);
            log.debug("当前缓存对象状态集合:{}", getEntityCacheMap().toString());
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(filePathAtDisk)));
            String line;
            while (!StringUtil.isBlank(line = br.readLine())) {
                E entity = convertStringToEntity(line);
                if (!ObjectUtil.isNull(entity) && computeFieldValue(entity, map)) {
                    addToEntityCacheMap(entity);
                    // 添加主键和查询参数的对应关系
                    K id = getIdValue(entity);
                    List<Map<String, Object>> mapList = keyToQueryParamMap.get(id);
                    if (ObjectUtil.isNull(mapList)) {
                        mapList = new ArrayList<>();
                        keyToQueryParamMap.put(id, mapList);
                    }
                    mapList.add(map);
                }
            }
        } catch (FileNotFoundException ignore) {
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            IoUtil.close(br);
        }
    }

    /**
     * 对比请求的属性是否完全相同
     *
     * @param entity 实体
     * @param map    属性名和属性值的对应关系
     * @return true: 所有指定的属性名和属性值，与磁盘上的文件一直
     */
    private boolean computeFieldValue(E entity, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            Field field = getEntityHolder().getField(name);
            Object realValue = ReflectUtil.get(field, entity);
            if (!value.equals(realValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将处理好的实体添加到map中
     */
    private void addToEntityCacheMap(E entity) {
        // 检查对象是否存在缓存中
        K idValue = getIdValue(entity);
        EntityWrap<E> entityWrap = getEntityCacheMap().get(idValue);
        if (ObjectUtil.isNull(entityWrap)) {
            entityWrap = new EntityWrap<>();
            entityWrap.setExpireTime(getExpireTime());
            entityWrap.setEntity(entity);
            entityWrap.setType(EntityWrap.Type.DEFAULT);
            getEntityCacheMap().put(getIdValue(entity), entityWrap);
        }
    }

    /**
     * 获取缓存的过期时间
     */
    protected long getExpireTime() {
        int expireTime = properties.getDb().getExpireTime();
        return System.currentTimeMillis() + expireTime * 60 * 1000;
    }

    /**
     * 将字符串转换为实体
     */
    private E convertStringToEntity(String line) {
        E entity = null;
        try {
            entity = JsonUtil.fromJson(line, getEntityClass());
        } catch (Exception ignore) {
        }
        return entity;
    }

    /**
     * 生成指定日期存储数据的持久化文件名称
     */
    private String getDiskFilePath() {
        String persistFileName = getEntityHolder().getEntityAnnotation().name();
        String path = properties.getDb().getPath();
        String fileName = persistFileName.concat(Constant.SUFFIX_JSON);
        return IoUtil.localPath(path, fileName);
    }

    /**
     * 将数据写出到磁盘
     */
    protected void writeDataToDisk() throws IOException {

        if (log.isDebugEnabled()) {
            log.debug("{} - 写出数据到磁盘……", getClass());
            log.debug("当前缓存对象状态集合:{}", getEntityCacheMap().toString());
        }

        long time = System.currentTimeMillis();

        Iterator<EntityWrap<E>> entityWraps = getEntityCacheMap().values().iterator();
        while (entityWraps.hasNext()) {
            EntityWrap<E> entityWrap = entityWraps.next();

            // 处理操作类型
            EntityWrap.Type type = entityWrap.getType();
            E entity = entityWrap.getEntity();
            entityGroupMap.get(type).add(entity);
            // 如果是删除状态，必须从缓存中移除
            if (type == EntityWrap.Type.DELETE) {
                entityWraps.remove();
                removeFromQueryParamSet(entity);
                if (log.isDebugEnabled()) {
                    log.debug("实体已被置为删除状态,移除缓存：{}", entity);
                    log.debug("当前缓存参数集合:{}", queryParamSet);
                    log.debug("当前缓存参数Map:{}", keyToQueryParamMap);
                }
                continue;
            }
            // 增加、更新状态，将实体状态置为正常
            entityWrap.setType(EntityWrap.Type.DEFAULT);

            // 处理过期时间
            long expireTime = entityWrap.getExpireTime();
            if (expireTime < time) {
                entityWraps.remove();
                removeFromQueryParamSet(entity);
                if (log.isDebugEnabled()) {
                    log.debug("实体超出过期时间:{}", entity);
                    log.debug("当前缓存参数集合:{}", queryParamSet);
                    log.debug("当前缓存参数Map:{}", keyToQueryParamMap);
                }
            }
        }

        // 处理分组集合
        // 这里的顺序一定不能反
        removeFromDisk();
        appendToDisk();

        // 重置分组器
        resetEntityGroupMap();

        // 移除查询缓存中无效的key
        removeUnusedQueryParam();

    }

    /**
     * 移除查询缓存中无效的key
     */
    private void removeUnusedQueryParam() {
        if (log.isDebugEnabled()) {
            log.debug("清理无效查询参数开始:{}", queryParamSet);
        }
        Set<Map<String, Object>> exists = new HashSet<>();
        for (List<Map<String, Object>> list : keyToQueryParamMap.values()) {
            for (Map<String, Object> map : list) {
                if (queryParamSet.contains(map)) {
                    exists.add(map);
                }
            }
        }
        Iterator<Map<String, Object>> queryParam = queryParamSet.iterator();
        while (queryParam.hasNext()) {
            Map<String, Object> map = queryParam.next();
            if (!exists.contains(map)) {
                if (log.isDebugEnabled()) {
                    log.debug("发现无效查询参数缓存:{}", map);
                }
                queryParam.remove();
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("清理无效查询参数缓存结束:{}", queryParamSet);
        }
        exists.clear();
    }

    /**
     * 追加资源到文件
     */
    private void appendToDisk() throws IOException {
        List<E> update = entityGroupMap.get(EntityWrap.Type.UPDATE);
        List<E> add = entityGroupMap.get(EntityWrap.Type.ADD);
        add.addAll(update);
        if (add.isEmpty()) {
            return;
        }
        String filepath = getDiskFilePath();
        try {
            appendFile(filepath, add);
        } catch (FileNotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("文件[{}]未找到, 创建新文件后重试", filepath);
            }
            IoUtil.createFile(filepath);
            appendFile(filepath, add);
        }
    }

    /**
     * 将处理好的实体列表，写出到磁盘
     */
    private synchronized void appendFile(String file, List<E> list) throws FileNotFoundException {

        if (log.isDebugEnabled()) {
            log.debug("添加数据到文件: [{}]", file);
        }

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), Charset.forName(Constant.DEFAULT_CHARSET)))) {
            list.forEach(e -> {
                removeFromQueryParamSet(e);
                pw.println(JsonUtil.toJson(e));
            });
        }
    }

    /**
     * 从查询参数缓存中移除对应的id
     */
    private void removeFromQueryParamSet(E e) {
        // 清楚实体对应的查询查询缓存
        K id = getIdValue(e);
        List<Map<String, Object>> mapList = keyToQueryParamMap.get(id);

        if (!ObjectUtil.isNull(mapList)) {
            for (Map<String, Object> map : mapList) {
                queryParamSet.remove(map);
            }
            mapList.clear();
        }
        keyToQueryParamMap.remove(id);
    }

    /**
     * 从文件中移除
     */
    private void removeFromDisk() throws IOException {
        List<E> update = entityGroupMap.get(EntityWrap.Type.UPDATE);
        List<E> delete = entityGroupMap.get(EntityWrap.Type.DELETE);

        if (!update.isEmpty()) {
            delete.addAll(update);
        }
        if (!delete.isEmpty()) {
            copyFile(getDiskFilePath(), delete);
        }
    }

    private synchronized void copyFile(String filepath, List<E> list) throws IOException {

        if (log.isDebugEnabled()) {
            log.debug("拷贝数据到文件: [{}]", filepath);
        }

        Set<K> idSet = list.stream()
                .map(this::getIdValue)
                .collect(Collectors.toSet());
        IoUtil.createFile(filepath);
        String tmpFile = filepath.concat(".tmp");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(tmpFile, true), Charset.forName(Constant.DEFAULT_CHARSET)));
             BufferedReader br = new BufferedReader(new InputStreamReader(
                     new FileInputStream(filepath), Charset.forName(Constant.DEFAULT_CHARSET)))) {
            String line;
            while (!StringUtil.isBlank(line = br.readLine())) {
                E entity = JsonUtil.fromJson(line, getEntityHolder().getEntityClazz());
                K id = getIdValue(entity);
                // 写出所有未被删除的记录
                if (!idSet.contains(id)) {
                    pw.println(line);
                }
            }
        }
        // 删除原文件，并将临时文件替换成原文件
        IoUtil.delete(filepath);
        IoUtil.rename(tmpFile, filepath);
        IoUtil.delete(tmpFile);
    }

    /**
     * 获取id的值
     */
    @SuppressWarnings("unchecked")
    protected K getIdValue(E entity) {
        Object id = ReflectUtil.get(getEntityHolder().getIdField(), entity);
        Assert.notNull(id, "主键不能为null");
        return (K) id;
    }

    @Override
    public void destroy() throws Exception {
        clear();
        // 停止线程池
        executor.shutdownNow();
    }

    @Override
    public void clear() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("开始持久化数据,将内存数据写入磁盘……");
        }

        writeDataToDisk();

        entityCacheMap.clear();
        // 清空查询缓存
        keyToQueryParamMap.clear();
        queryParamSet.clear();

        if (log.isDebugEnabled()) {
            log.debug("内存数据持久化成功, 缓存清理成功");
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        properties = applicationContext.getBean(ConfigProperties.class);
        Assert.notNull(properties, "配置不能不能为空, type[" + ConfigProperties.class.getName() + "]");
    }

    /**
     * 获取缓存数据
     */
    public Map<K, EntityWrap<E>> getEntityCacheMap() {
        return entityCacheMap;
    }

}
