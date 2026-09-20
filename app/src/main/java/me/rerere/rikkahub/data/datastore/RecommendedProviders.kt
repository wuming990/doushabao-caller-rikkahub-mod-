package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting

/**
 * 推荐的提供商列表，在提供商设置页右上角的推荐 Sheet 中展示。
 *
 * v305（用户要求「去掉供应商里面的广告位置」）：这份清单原本只有 4 条
 * （AiHubMix / APIMart / 随想AI网关 / MaruCode），全部带推广文案与返佣链接
 * （`?aff=`、邀请码），性质是广告位而非功能入口，因此整表清空。
 * 保留这个空列表而不是删掉文件，是为了让上游将来往这里加东西时仍有一处落点，
 * 也让设置页可以按「列表为空就不显示推荐按钮」判断（见 SettingProviderPage 的
 * RecommendProviderButton）。清掉的内容在 v304 的 FINAL 快照里有完整原件，
 * 需要恢复某条时从那里取。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = emptyList()
