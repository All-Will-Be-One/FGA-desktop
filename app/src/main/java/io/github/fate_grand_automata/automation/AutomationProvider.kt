package io.github.fate_grand_automata.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.fate_grand_automata.scripts.prefs.IPreferences

/** Only the already-authorized local ADB shell or this app UID can invoke RPC. */
class AutomationProvider:ContentProvider() {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies { fun preferences():IPreferences }
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val uid=Binder.getCallingUid()
        if(uid!=2000 && uid!=Process.myUid()) throw SecurityException("Automation requires the local ADB shell UID")
        require(method=="rpc" && arg!=null) { "Use the versioned high-level RPC" }
        val context=requireNotNull(context).applicationContext
        AutomationBridge.initialize(context,EntryPoints.get(context,Dependencies::class.java).preferences())
        return Bundle().apply { putString("json",AutomationBridge.request(arg)) }
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor? = throw SecurityException("No query surface")
    override fun getType(uri:Uri):String? = null
    override fun insert(uri:Uri,values:ContentValues?):Uri? = throw SecurityException("No raw writes")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int = throw SecurityException("No raw writes")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int = throw SecurityException("No raw writes")
}
