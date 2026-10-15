package org.wannes.plugModCore.module;
import org.wannes.plugModCore.api.*;
import org.wannes.plugmodcore.network.*;
import java.time.Duration;
/** Lets genuinely platform-neutral modules reuse the established Paper lifecycle/resources. */
final class SharedModuleAdapter implements org.wannes.plugModCore.api.Module,ModuleWebApi {
    final PlatformModule delegate;PlatformContext platform;
    SharedModuleAdapter(PlatformModule delegate){this.delegate=delegate;}
    private PlatformContext adapt(ModuleContext context){return new PlatformContext(){public String id(){return context.getModuleId();}public String platform(){return "PAPER";}public java.nio.file.Path dataFolder(){return context.getDataFolder().toPath();}public <T extends AutoCloseable>T manage(T r){return context.manage(r);}public <T>void registerService(Class<T> t,T s){context.registerService(t,s);}public <T>T service(Class<T> t){return context.getService(t);}public AutoCloseable schedule(Runnable task,Duration delay,Duration repeat){var handle=context.schedule(task,Math.max(0,delay.toMillis()/50),repeat.isZero()?0:Math.max(1,repeat.toMillis()/50),false);return handle::cancel;}public void log(String message){context.getLogger().info(message);}};}
    public void onLoad(ModuleContext context){platform=adapt(context);try{delegate.onLoad(platform);}catch(Exception e){throw new IllegalStateException(e);}}
    public void onEnable(ModuleContext context){platform=adapt(context);try{delegate.onEnable(platform);}catch(Exception e){throw new IllegalStateException(e);}}
    public void onDisable(){try{delegate.onDisable();}catch(Exception e){throw new IllegalStateException(e);}}
    public ApiResponse handleApiRequest(String method,String path,java.util.Map<String,String> params){if(!(delegate instanceof PlatformWeb web))return ApiResponse.notFound("No module WebAPI");var reply=web.request(method,path,params);return new ApiResponse(reply.status(),reply.contentType(),reply.body());}
}
