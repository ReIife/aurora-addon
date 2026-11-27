#!/usr/bin/env python3
"""注册表静态校验：分类名 / 模块名 / 语言键。

起因（1.5.0 的真实事故）：插件自建了一个名为 "Combat" 的分类，与 Evernight 内置分类重名。
1.0.3 的 `registerCategory` 不校验重名（静默多出一个分类面板），1.2.4 起改为抛
`IllegalStateException`，导致 `onInitialize` 整体中止——玩家进游戏后「一个功能都看不到」。

这个脚本在构建前把同类问题挡下来：

1. `registerCategory("X")` 的 X 不能是内置分类名，也不能重复；
2. 模块 `super("<Name>", ...)` 的 Name 在本插件内不能重复；
3. 每个模块名都要有 `addons.aurora.names.modules.<Name>` 与
   `addons.aurora.descriptions.modules.<Name>` 语言键（中英各一份）。

用法：python scripts/check-registration.py
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'main', 'java')
LANG = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'aurora', 'lang')

# Evernight 1.2.4 的内置分类（ModuleCategory 的常量）
BUILT_IN_CATEGORIES = {
    'Combat', 'Misc', 'Render', 'Movement', 'Player', 'Auto', 'Exploit', 'Client', 'HUD',
}

CATEGORY_CALL = re.compile(r'registerCategory\(\s*(?:[A-Za-z_][\w.]*\s*,\s*)?"([^"]+)"')
MODULE_SUPER = re.compile(r'super\(\s*"([A-Za-z0-9_]+)"\s*,')


def java_files():
    for root, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith('.java'):
                yield os.path.join(root, name)


def main():
    errors = []
    categories = {}
    modules = {}

    for path in java_files():
        rel = os.path.relpath(path, SRC).replace(os.sep, '/')
        # 基类 AuroraModule 的 super("aurora", ...) 里第一个参数是插件 id，不是模块名
        if rel.endswith('modules/AuroraModule.java'):
            continue
        text = open(path, encoding='utf-8').read()

        for name in CATEGORY_CALL.findall(text):
            categories.setdefault(name, []).append(rel)
        for name in MODULE_SUPER.findall(text):
            modules.setdefault(name, []).append(rel)

    for name, files in categories.items():
        if name in BUILT_IN_CATEGORIES:
            errors.append(
                '分类名与 Evernight 内置分类冲突："{0}"（{1}）\n'
                '    → 内置分类请直接用 ModuleCategory.{2}，不要自建同名分类'
                .format(name, ', '.join(files), name.upper()))
        if len(files) > 1:
            errors.append('分类名重复注册："{0}"（{1}）'.format(name, ', '.join(files)))

    for name, files in modules.items():
        if len(files) > 1:
            errors.append('模块名重复："{0}"（{1}）'.format(name, ', '.join(files)))

    for lang_file in ('en_us.json', 'zh_cn.json'):
        path = os.path.join(LANG, lang_file)
        table = json.load(open(path, encoding='utf-8'))
        for name in modules:
            for key in ('addons.aurora.names.modules.', 'addons.aurora.descriptions.modules.'):
                if key + name not in table:
                    errors.append('缺少语言键：{0}（{1}）'.format(key + name, lang_file))
        for name in categories:
            key = 'addons.aurora.names.categories.' + name
            if key not in table:
                errors.append('缺少语言键：{0}（{1}）'.format(key, lang_file))

    print('分类 {0} 个：{1}'.format(len(categories), ', '.join(sorted(categories)) or '-'))
    print('模块 {0} 个：{1}'.format(len(modules), ', '.join(sorted(modules)) or '-'))

    if errors:
        print('\n失败：{0} 个问题'.format(len(errors)))
        for error in errors:
            print('  - ' + error)
        return 1

    print('\n通过：0 个错误')
    return 0


if __name__ == '__main__':
    sys.exit(main())
