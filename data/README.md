# data/ 放什么

把你的课表页存成这个目录下的 `course.html`，然后按根目录 README 里的命令往下跑。

两种来法：

1. 工具抓：`python scripts\fetch_course_html.py --login-url "<登录页地址>"`
2. 浏览器里自己登录 → 打开课表页 → 另存为 `data/course.html`

`*.html` 已经写进 `.gitignore`，**不会被提交**：课表里有你的学号、班级、上课地点，
属于个人数据，只留在本机。
