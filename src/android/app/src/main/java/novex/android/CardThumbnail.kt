package novex.android

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import novex.content.ContentRef

/** 只为当前可见条目解码小图；不改变资源本体或模型看图状态。 */
@Composable internal fun CardThumbnail(ref:ContentRef,model:CardSessionModel,description:String="图片缩略图",modifier:Modifier=Modifier.size(56.dp),maxEdge:Int=256) {
    var bitmap by remember(ref,maxEdge){mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(ref,maxEdge){mutableStateOf(false)}
    LaunchedEffect(ref,maxEdge) {
        try {bitmap=requireNotNull(model.image(ref,maxEdge))}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){failed=true}
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(NovexColors.SurfaceMuted),contentAlignment=Alignment.Center) {
        val loaded=bitmap
        if(loaded!=null)Image(loaded.asImageBitmap(),description,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Text(if(failed)"无法读取" else "载入中")
    }
}

@Composable internal fun RoleThumbnail(role:novex.content.ContentDocument,model:CardSessionModel) {
    val id=role.appearance.avatarResourceId?:role.appearance.coverResourceId
    val resource=role.resources.firstOrNull {it.id==id}
    if(resource!=null)CardThumbnail(resource.content,model,"角色头像")
    else novex.android.ui.NovexArtwork(novex.android.ui.NovexArtworkKind.CHARACTER,role.id,null,"角色头像占位",Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
}

/** 圆形头像：落地页角色行与角色卡的叠放头像槽位共用。 */
@Composable internal fun RoleAvatarImage(ref:ContentRef,model:CardSessionModel,size:androidx.compose.ui.unit.Dp,modifier:Modifier=Modifier) {
    var bitmap by remember(ref){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(ref){try{bitmap=model.image(ref,512)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}}
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(NovexColors.SurfaceMuted),contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),"角色头像",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
    }
}

@Composable internal fun RoleAvatar(role:novex.content.ContentDocument,model:CardSessionModel,size:androidx.compose.ui.unit.Dp=40.dp) {
    val id=role.appearance.avatarResourceId?:role.appearance.coverResourceId
    val resource=role.resources.firstOrNull {it.id==id}
    if(resource!=null)RoleAvatarImage(resource.content,model,size)
    else novex.android.ui.NovexArtwork(novex.android.ui.NovexArtworkKind.CHARACTER,role.id,null,"角色头像占位",Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape))
}
