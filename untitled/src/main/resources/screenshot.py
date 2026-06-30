#!/usr/bin/env python3
"""
kkrb.net 数据提取脚本。
两种模式：
  screenshot: python3 screenshot.py <task_name> <output_path>
  extract:    python3 screenshot.py --extract <task_name>
              输出 JSON 到 stdout（文本数据提取，无图片）
"""
import sys
import json
import time
from playwright.sync_api import sync_playwright

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

TASKS = {
    "kkrb-overview": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-swat-product-container",
        "needs_profit_switch": True,
        "label": "特勤处",
    },
    "stock-chart": {
        "url": "https://example.com/chart",
        "selector": ".chart-container"
    },
    "kkrb-overview-2": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bcic-container",
        "label": "脑机",
    },
    "kkrb-overview-3": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
        "label": "密码",
    },
    "kkrb-overview-1-1": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#layui-table-box",
    },
    "kkrb-overview-1-2": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#layui-table-box",
    },
    "kkrb-overview-1-3": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
    },
    "kkrb-overview-1-4": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
    }
}

def take_screenshot(task_name, output_path):
    if task_name not in TASKS:
        raise ValueError(f"Unknown task: {task_name}")

    config = TASKS[task_name]
    selector = config["selector"]

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-web-security",
                "--disable-extensions",
                "--disable-plugins",
                "--disable-software-rasterizer",
                "--disable-setuid-sandbox",
                "--disable-features=site-per-process",
                "--disable-features=VizDisplayCompositor",
            ]
        )
        page = browser.new_page()

        # 设置真实 User-Agent
        page.set_extra_http_headers({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        })

        try:
            print(f"🌐 访问: {config['url']}", file=sys.stderr)
            page.goto(config["url"], wait_until="domcontentloaded")

            print(f"🔍 当前页面标题: {page.title()}", file=sys.stderr)
            print(f"🔍 当前页面 URL: {page.url}", file=sys.stderr)

            # 调试截图（初始状态）
            page.screenshot(path="/tmp/debug-after-goto.png")
            print("📸 已保存初始状态截图: /tmp/debug-after-goto.png", file=sys.stderr)

            # 等待 JS 渲染（Layui 初始化需要时间）
            time.sleep(2)  # 延长至 2 秒

            # ✅ 精准关闭 layui 弹窗
            try:
                page.wait_for_selector(".layui-layer-dialog", timeout=10000)
                print("🔍 发现弹窗，准备关闭...", file=sys.stderr)
                page.click(".layui-layer-btn0")
                print("✅ 已点击‘确定’按钮关闭弹窗", file=sys.stderr)
                page.wait_for_timeout(500)  # 稍等确保弹窗完全消失
            except Exception as e:
                print(f"⚠️ 弹窗未找到或点击失败（可能已自动关闭）: {e}", file=sys.stderr)

            # ✅ 条件性触发 profitSwitch（增强版）
            if config.get("needs_profit_switch", False):
                try:
                    container_selector = "#profitSwitch + .layui-unselect"
                    print(f"🔍 等待利润开关容器出现: {container_selector}", file=sys.stderr)
                    page.wait_for_selector(container_selector, timeout=12000)

                    # 获取当前 class
                    current_class = page.locator(container_selector).get_attribute("class") or ""
                    is_on = "layui-form-onswitch" in current_class

                    print(f"🔧 当前开关 class: '{current_class}'", file=sys.stderr)
                    print(f"📊 当前开关状态: {'开启（小时利润）' if is_on else '关闭（总利润）'}", file=sys.stderr)

                    if not is_on:
                        print("🔄 正在点击开关容器以切换到‘小时利润’模式...", file=sys.stderr)
                        # 👉 关键：点击可视化 div，不是 input
                        page.click(container_selector)
                        page.wait_for_timeout(1200)  # 给 JS 足够时间加载新数据

                        # 验证是否成功
                        new_class = page.locator(container_selector).get_attribute("class") or ""
                        new_is_on = "layui-form-onswitch" in new_class
                        print(f"🔧 切换后 class: '{new_class}'", file=sys.stderr)
                        if new_is_on:
                            print("✅ 开关已成功切换为‘小时利润’模式", file=sys.stderr)
                        else:
                            print("❌ 开关点击后仍未开启！可能被阻止或 JS 未响应", file=sys.stderr)
                            page.screenshot(path="/tmp/debug-switch-fail.png")
                            print("📸 已保存开关操作失败截图: /tmp/debug-switch-fail.png", file=sys.stderr)
                    else:
                        print("ℹ️ 开关已处于‘小时利润’模式，无需操作", file=sys.stderr)

                except Exception as e:
                    print(f"💥 利润开关操作异常: {e}", file=sys.stderr)
                    page.screenshot(path="/tmp/debug-switch-error.png")
                    print("📸 已保存异常状态截图: /tmp/debug-switch-error.png", file=sys.stderr)

            # 等待目标容器加载
            try:
                page.wait_for_selector(selector, timeout=15000)
                print(f"✅ 目标元素 '{selector}' 已加载", file=sys.stderr)
            except:
                print(f"⚠️ 未找到目标元素 '{selector}'，尝试全页截图", file=sys.stderr)

            # 👇 滚动到目标区域
            try:
                locator = page.locator(selector)
                locator.scroll_into_view_if_needed(timeout=5000)
                print("✅ 已滚动目标区域到视口内", file=sys.stderr)
            except Exception as e:
                print(f"⚠️ 滚动失败（可能元素不可滚动）: {e}", file=sys.stderr)

            # 👇 额外向上滚动一点（避开底部固定层）
            try:
                page.evaluate("window.scrollBy(0, 150);")
                print("▲ 额外向上滚动 150px 以避开底部遮挡", file=sys.stderr)
                page.screenshot(path="/tmp/debug-after-scroll.png")
                print("📸 已保存滚动后状态: /tmp/debug-after-scroll.png", file=sys.stderr)
            except Exception as e:
                print(f"⚠️ 额外滚动失败: {e}", file=sys.stderr)

            # 👇 尝试局部截图
            success = False
            try:
                locator = page.locator(selector)
                box = locator.bounding_box()
                if box and box["width"] > 0 and box["height"] > 0:
                    locator.screenshot(path=output_path)
                    print(f"📸 成功保存局部截图: {output_path}", file=sys.stderr)
                    success = True
                else:
                    raise Exception("Element has no visible dimensions")
            except Exception as e:
                print(f"⚠️ 局部截图失败 ({e})，尝试全页截图", file=sys.stderr)

            if not success:
                page.screenshot(path=output_path, full_page=True)
                print(f"📸 使用全页截图: {output_path}", file=sys.stderr)

                if HAS_PIL:
                    try:
                        img = Image.open(output_path)
                        width, height = img.size
                        cropped = img.crop((0, 0, width, max(0, height - 100)))
                        cropped.save(output_path)
                        print("✂️ 已自动裁剪底部 100px", file=sys.stderr)
                    except Exception as e:
                        print(f"⚠️ 自动裁剪失败: {e}", file=sys.stderr)

        except Exception as e:
            page.screenshot(path="/tmp/debug-final.png")
            print(f"💥 截图流程失败！最终状态已保存到 /tmp/debug-final.png", file=sys.stderr)
            raise e
        finally:
            browser.close()

    print(f"Saved to {output_path}")


# ======== 文本解析函数 ========

def parse_swat_products(raw_text):
    """解析特勤处文本 — 用工作台名作为分隔符，字段间有空行。"""
    products = []
    WORKBENCHES = ["技术中心", "工作台", "制药台", "防具台"]

    # 找到每个工作台的起始位置
    segments = []
    for wb in WORKBENCHES:
        idx = raw_text.find(wb)
        if idx >= 0:
            # 从工作台名后取到下一个工作台名或文本末尾
            rest = raw_text[idx:]
            # 找下一个工作台名（跳过当前这个）
            end = len(rest)
            for wb2 in WORKBENCHES:
                if wb2 == wb:
                    continue
                pos = rest.find(wb2, len(wb))
                if 0 < pos < end:
                    end = pos
            segments.append(rest[:end])

    for seg in segments:
        # 合并多行，去掉空行
        lines = [l.strip() for l in seg.split("\n") if l.strip()]
        if len(lines) < 3:
            continue
        # 格式: 工作台名 / ... / 当前利润：xxx / ... / 理想售价：xxx / ... / 卖：xxx / ... / 产品名
        p = {"workbench": lines[0], "product": lines[-1]}
        for line in lines[1:-1]:
            if "当前利润" in line:
                p["profit"] = parse_int(line)
            elif "理想售价" in line:
                p["idealPrice"] = parse_int(line)
            elif line.startswith("卖") or "卖：" in line:
                p["sellTime"] = line.split("：")[-1].strip() if "：" in line else line.replace("卖", "").strip()
        if "workbench" in p and "product" in p:
            products.append(p)
    return products


def parse_bcic_items(raw_text):
    """解析脑机可扫描物品 — 每行一个物品名。"""
    items = []
    for line in raw_text.split("\n"):
        line = line.strip()
        if line and len(line) > 1 and "脑机" not in line and "扫描" not in line:
            items.append({"name": line})
    return items


def parse_door_passwords(raw_text):
    """解析密码门 — 用地图名作为分隔符，中间有空行。"""
    import re
    passwords = []
    MAPS = ["零号大坝", "长弓溪谷", "巴克什", "航天基地", "潮汐监狱"]

    for map_name in MAPS:
        idx = raw_text.find(map_name)
        if idx < 0:
            continue
        # 从地图名后取到下一个地图名或末尾
        rest = raw_text[idx:]
        end = len(rest)
        for m2 in MAPS:
            if m2 == map_name:
                continue
            pos = rest.find(m2, len(map_name))
            if 0 < pos < end:
                end = pos
        block = rest[:end]
        # 压缩空行
        lines = [l.strip() for l in block.split("\n") if l.strip()]
        # 格式: 地图名 / 4位数字密码 / 日期 更新
        for line in lines:
            if re.match(r"^\d{4}$", line):
                pwd = line
                date_str = ""
                # 往后找日期行
                for l2 in lines[lines.index(line)+1:]:
                    if "更新" in l2:
                        date_str = l2.replace("更新", "").strip()
                        break
                passwords.append({"map": map_name, "password": pwd, "updateDate": date_str})
                break
    return passwords


def parse_int(line):
    """从 '当前利润：35443' 提取整数"""
    import re
    s = line.split("：")[-1].strip() if "：" in line else line
    nums = re.findall(r"\d+", s.replace(",", ""))
    return int(nums[0]) if nums else 0


# ======== 主提取函数 ========

def extract_text(task_name):
    """提取目标容器内的结构化文本数据，返回 JSON 字符串。"""
    if task_name not in TASKS:
        raise ValueError(f"Unknown task: {task_name}")

    config = TASKS[task_name]
    selector = config["selector"]

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--disable-web-security", "--disable-extensions", "--disable-plugins",
                "--disable-software-rasterizer", "--disable-setuid-sandbox",
                "--disable-features=site-per-process",
                "--disable-features=VizDisplayCompositor",
            ]
        )
        page = browser.new_page()
        page.set_extra_http_headers({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        })

        try:
            print(f"📊 提取模式: {config.get('label', task_name)}", file=sys.stderr)
            page.goto(config["url"], wait_until="domcontentloaded")
            time.sleep(2)

            # 关闭弹窗
            try:
                page.wait_for_selector(".layui-layer-dialog", timeout=8000)
                page.click(".layui-layer-btn0")
                page.wait_for_timeout(500)
                print("✅ 弹窗已关闭", file=sys.stderr)
            except Exception:
                pass

            # 利润开关
            if config.get("needs_profit_switch", False):
                try:
                    sw = "#profitSwitch + .layui-unselect"
                    page.wait_for_selector(sw, timeout=10000)
                    cls = page.locator(sw).get_attribute("class") or ""
                    if "layui-form-onswitch" not in cls:
                        page.click(sw)
                        page.wait_for_timeout(1200)
                        print("✅ 已切换到小时利润", file=sys.stderr)
                except Exception as e:
                    print(f"⚠️ 利润开关失败: {e}", file=sys.stderr)

            # 等待目标容器
            try:
                page.wait_for_selector(selector, timeout=15000)
            except Exception:
                print(f"❌ 未找到容器: {selector}", file=sys.stderr)
                return json.dumps({"error": f"未找到目标容器 {selector}"}, ensure_ascii=False)

            # 滚动到目标
            try:
                page.locator(selector).scroll_into_view_if_needed(timeout=5000)
                page.evaluate("window.scrollBy(0, 150);")
            except Exception:
                pass

            container = page.locator(selector)
            raw_text = container.inner_text().strip()

            result = {
                "label": config.get("label", task_name),
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            }

            if task_name == "kkrb-overview":
                result["products"] = parse_swat_products(raw_text)
            elif task_name == "kkrb-overview-2":
                result["items"] = parse_bcic_items(raw_text)
            elif task_name == "kkrb-overview-3":
                result["passwords"] = parse_door_passwords(raw_text)
            else:
                result["text"] = raw_text

            item_count = len(result.get("products", result.get("items", result.get("passwords", []))))
            print(f"✅ 提取完成: {item_count} 条记录", file=sys.stderr)
            return json.dumps(result, ensure_ascii=False)

        except Exception as e:
            print(f"💥 提取失败: {e}", file=sys.stderr)
            return json.dumps({"error": str(e)}, ensure_ascii=False)
        finally:
            browser.close()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage:", file=sys.stderr)
        print("  screenshot: python3 screenshot.py <task_name> <output_path>", file=sys.stderr)
        print("  extract:    python3 screenshot.py --extract <task_name>", file=sys.stderr)
        sys.exit(1)

    if sys.argv[1] == "--extract":
        if len(sys.argv) != 3:
            print("Usage: python3 screenshot.py --extract <task_name>", file=sys.stderr)
            sys.exit(1)
        try:
            result = extract_text(sys.argv[2])
            print(result)
        except Exception as e:
            print(json.dumps({"error": str(e)}, ensure_ascii=False))
            sys.exit(1)
    else:
        if len(sys.argv) != 3:
            print("Usage: python3 screenshot.py <task_name> <output_path>", file=sys.stderr)
            sys.exit(1)
        try:
            take_screenshot(sys.argv[1], sys.argv[2])
        except Exception as e:
            print(f"ERROR: {str(e)}", file=sys.stderr)
            sys.exit(1)