package com.rd.zngp;

import com.rd.zngp.config.Config;
import com.rd.zngp.http.NettyHttpServer;
import com.rd.zngp.model.InspectionItem;
import com.rd.zngp.model.InspectionTemplate;
import com.rd.zngp.store.Store;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point, mirroring server/main.go and server/seed.go.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            // Load config
            String cfgPath = "cfg.yml";
            if (args.length > 0) {
                cfgPath = args[0];
            }

            Config cfg = Config.load(cfgPath);
            log.info("配置加载完成: system={}", cfg.system.name);

            // Initialize store
            Store store = new Store(cfg.database.path);
            store.init();

            // Ensure default admin user
            ensureDefaultAdmin(store);
            log.info("默认管理员已就绪");

            // Seed templates
            seedTemplates(store);

            // Start Netty HTTP server
            NettyHttpServer server = new NettyHttpServer(store);
            int port = Integer.parseInt(cfg.server.port);
            server.start(cfg.server.host, port);

        } catch (Exception e) {
            log.error("启动失败", e);
            System.exit(1);
        }
    }

    /**
     * Create default admin user if no users exist.
     */
    private static void ensureDefaultAdmin(Store store) {
        try {
            long count = store.countUsers();
            if (count > 0) return;

            String hash = BCrypt.hashpw(Config.appConfig.auth.password, BCrypt.gensalt());
            User user = new User();
            user.username = Config.appConfig.auth.username;
            user.passwordHash = hash;
            user.role = "admin";
            user.createdAt = LocalDateTime.now();
            store.createUser(user);
        } catch (Exception e) {
            log.error("创建默认管理员失败", e);
        }
    }

    /**
     * Seed default inspection templates if none exist.
     */
    private static void seedTemplates(Store store) {
        try {
            List<InspectionTemplate> templates = store.listTemplates();
            if (templates != null && !templates.isEmpty()) {
                return; // Already seeded
            }

            List<InspectionTemplate> defaultTemplates = buildDefaultTemplates();
            for (InspectionTemplate t : defaultTemplates) {
                store.createTemplate(t);
            }
            log.info("检查项模板已初始化");
        } catch (Exception e) {
            log.warn("种子模板插入失败: {}", e.getMessage());
        }
    }

    /**
     * Build default templates, mirroring server/seed.go.
     */
    private static List<InspectionTemplate> buildDefaultTemplates() {
        List<InspectionTemplate> templates = new ArrayList<>();

        // Template 1: 燃气入户安检
        InspectionTemplate t1 = new InspectionTemplate();
        t1.name = "燃气入户安检";
        t1.description = "检查燃气入户安检过程是否合规";
        t1.category = "gas_safety";
        t1.isActive = true;
        t1.createdAt = LocalDateTime.now();
        t1.updatedAt = LocalDateTime.now();
        t1.items = new ArrayList<>();
        t1.items.add(createItem(1, "亮明身份", "安检员是否自报姓名、工号、单位", "服务礼仪", true, 1));
        t1.items.add(createItem(2, "说明来意", "是否说明本次入户安检的目的", "服务礼仪", true, 1));
        t1.items.add(createItem(3, "检查燃气管道", "检查管道及连接处是否漏气（用检漏仪或肥皂水）", "安全检查", true, 3));
        t1.items.add(createItem(4, "检查燃气表", "检查燃气表运行状态是否正常", "安全检查", true, 2));
        t1.items.add(createItem(5, "检查燃气灶具", "检查灶具是否合规、有无熄火保护装置", "安全检查", true, 2));
        t1.items.add(createItem(6, "检查燃气热水器", "检查热水器安装是否合规（排烟管道、通风等）", "安全检查", true, 2));
        t1.items.add(createItem(7, "检查报警器", "检查燃气泄漏报警器是否工作正常", "安全检查", true, 2));
        t1.items.add(createItem(8, "检查通风条件", "检查用气场所通风是否良好", "安全检查", true, 1));
        t1.items.add(createItem(9, "检查软管/阀门", "检查连接软管是否老化、阀门是否完好", "安全检查", true, 2));
        t1.items.add(createItem(10, "告知安全事项", "是否向用户告知安全用气注意事项", "安全宣传", true, 1));
        t1.items.add(createItem(11, "隐患告知", "发现隐患时是否明确告知用户并记录", "安全处理", true, 2));
        t1.items.add(createItem(12, "用户签字确认", "是否让用户签字确认安检结果", "流程规范", true, 1));
        templates.add(t1);

        // Template 2: 客户拜访合规检查
        InspectionTemplate t2 = new InspectionTemplate();
        t2.name = "客户拜访合规检查";
        t2.description = "检查客户拜访过程是否合规";
        t2.category = "customer_visit";
        t2.isActive = true;
        t2.createdAt = LocalDateTime.now();
        t2.updatedAt = LocalDateTime.now();
        t2.items = new ArrayList<>();
        t2.items.add(createItem(1, "预约确认", "是否提前与客户确认拜访时间", "拜访准备", true, 1));
        t2.items.add(createItem(2, "自我介绍", "是否自报姓名、公司、职位", "服务礼仪", true, 1));
        t2.items.add(createItem(3, "明确拜访目的", "是否说明本次拜访的目的和议程", "服务礼仪", true, 1));
        t2.items.add(createItem(4, "了解客户需求", "是否主动询问和了解客户当前需求", "沟通技巧", true, 2));
        t2.items.add(createItem(5, "产品/服务介绍", "是否清晰介绍产品或服务的特点和优势", "销售技巧", false, 1));
        t2.items.add(createItem(6, "客户异议处理", "是否认真听取并回应客户的疑问和顾虑", "沟通技巧", true, 2));
        t2.items.add(createItem(7, "记录关键信息", "是否记录客户反馈的重要信息", "流程规范", true, 1));
        t2.items.add(createItem(8, "明确后续行动", "是否与客户明确下一步行动和时间节点", "流程规范", true, 2));
        t2.items.add(createItem(9, "礼貌结束", "是否礼貌结束拜访并表示感谢", "服务礼仪", true, 1));
        t2.items.add(createItem(10, "遵守合规要求", "是否遵守行业合规要求（如不得虚假宣传、不得承诺无法兑现的条件）", "合规", true, 2));
        templates.add(t2);

        return templates;
    }

    private static InspectionItem createItem(int number, String name, String desc, String category, boolean required, int weight) {
        InspectionItem item = new InspectionItem();
        item.itemNumber = number;
        item.name = name;
        item.description = desc;
        item.category = category;
        item.isRequired = required;
        item.weight = weight;
        item.createdAt = LocalDateTime.now();
        return item;
    }
}