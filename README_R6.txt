Junba AI Transcriber v3.8.2 APK R6
=================================

R6 以已實機成功的 R5 為基底，不回退 ScrollBarDrawable 修正。

本版改善：
1. 背景續跑
   - 前景 mediaProcessing Service
   - Partial WakeLock
   - 通知列顯示轉錄狀態
   - 可開啟 Android / vivo 省電最佳化設定
   - 返回鍵在轉錄中改為退到背景，不終止工作

2. Whisper 可理解的進度與預估時間
   - 可轉為 PCM WAV 時，自動採 5 分鐘安全區段
   - 顯示完成區段 x/y
   - 顯示已執行時間與預估剩餘時間
   - 每次成功後記錄該手機＋模型的實際 RTF，下次 ETA 會更接近實機速度

3. 繁體中文
   - Whisper / Gemini 結果均套用 Android ICU Simplified -> Traditional
   - 另有台灣常用詞 fallback

4. 完整時間軸
   - Whisper 保留真實段落開始/結束時間
   - Gemini 有時間碼就保留
   - Gemini 若只回純文字，R6 會以「[約 HH:MM:SS]」明確標示估算時間，不冒充精準時間碼
   - App 內結果區改為完整展開，由外層頁面上下瀏覽

5. KTV HTML
   - 參考 Windows「錄音核對播放器」概念
   - 自動輸出 _錄音核對.html 與 _字幕.vtt
   - HTML 可選擇原音檔、點逐字稿跳時間、播放時自動跟隨高亮

6. 暫停 / 繼續 / 停止並結算
   - Whisper 長錄音在 5 分鐘安全區段邊界暫停/停止
   - 「暫停」會先結算目前已完成內容
   - 「停止」會先保留快照，安全區段完成後再輸出中止版
   - Gemini 在當前 HTTP / AI 呼叫返回後才可安全暫停/停止
   - 結算資料夾包含原始音檔（原檔名不更動）、TXT、Markdown、VTT、KTV HTML

重要限制：
- 前景服務＋WakeLock 能大幅降低省電模式中止機率，但 Android/OEM 若強制終止整個 process，任何 App 都無法保證永不中斷。
- Whisper AAR 的單一 transcribe() 呼叫沒有即時 token/音框 progress callback，因此 R6 用 5 分鐘安全區段提供可驗證的真進度。
