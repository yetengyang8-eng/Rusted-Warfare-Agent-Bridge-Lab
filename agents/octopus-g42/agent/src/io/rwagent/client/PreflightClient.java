package io.rwagent.client;
import java.util.Map;
/** Read-only startup advice: never owns a control loop or submits a command. */
public final class PreflightClient {
    public static void main(String[] args) {
        int exit=1;
        try {
            if(args.length!=0)throw new IllegalArgumentException("Usage: PreflightClient (no arguments)");
            int port=Integer.getInteger("rwagent.port",47653);
            AgentClient.Response r=AgentClient.request("GET","http://127.0.0.1:"+port+"/economy/preflight");
            if(r.status!=200)throw new IllegalStateException("HTTP "+r.status+": "+r.body);
            Map<?,?> data=(Map<?,?>)Json.parse(r.body);
            System.out.println("建造者: "+data.get("eligibleBuilders")+"；空闲工厂: "+data.get("idleFactories")
                +"；忙碌工厂: "+data.get("busyFactories")+"；在建工厂: "+data.get("factoriesUnderConstruction"));
            String next=String.valueOf(data.get("recommendation"));
            if("RUN_DEVELOP".equals(next))System.out.println("可以运行 RW-Agent-Develop.bat，复用现有空闲工厂。");
            else if("WAIT_FACTORY_QUEUE".equals(next))System.out.println("工厂正在生产。请等队列完成后再运行预检，无需另建工厂。");
            else if("WAIT_FACTORY_CONSTRUCTION".equals(next))System.out.println("工厂仍在建造。请等完工后再运行预检。");
            else if("RUN_ECONOMY_OR_OPENING".equals(next))System.out.println("缺少工厂：运行 RW-Agent-Economy.bat 建厂并出一辆坦克；附近有可见空矿时，也可运行 RW-Agent-Opening.bat。");
            else if("PREPARE_BUILDER".equals(next))System.out.println("请先准备已完成、可控制的建造者。");
            else if("CHECK_FACTORY_ACTION".equals(next))System.out.println("已有工厂没有可用坦克生产项，请检查单位配置与工厂状态。");
            else System.out.println("请通过 RW-Agent-Start.bat 开启本地对局并允许指令。当前状态："+data.get("commandGuard"));
            System.out.println("本次仅检查，未下达游戏指令。资金、可见建造地点与游戏状态仍会在执行时重新核验。");
            System.out.println(r.body);
            exit=0;
        } catch(Exception e) {System.err.println("预检失败："+e+"\n请确认游戏已由当前版本 Agent 启动。");}
        System.exit(exit);
    }
}
