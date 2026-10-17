package eu.kanade.tachiyomi.extension.tr.norascans

import eu.kanade.tachiyomi.multisrc.initmanga.InitManga
import keiyoushi.annotation.Source

@Source
abstract class NoraScans : InitManga() {

    override val popularUrlSlug = "manga-ranking"

    override val latestUrlSlug = "recently-updated"
}
