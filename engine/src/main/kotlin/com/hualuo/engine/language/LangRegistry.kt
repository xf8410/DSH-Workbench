package com.hualuo.engine.language

/**
 * 语言注册表（查看器第一件）：扩展名/文件名到语言的**全量映射**——纪律是
 * 「不省略任何格式」：认得的给语言，不认得的也给"纯文本"照常显示，
 * 查看器永远不会说"这个格式不支持"。
 *
 * 元数据只挑染色要用的：行注释、块注释、字符串界定、关键词组（主流语言给全，
 * 其余语言给注释与字符串就够看）。表用紧凑 DSL 写，一行一族，好扫好改。
 */
object LangRegistry {

    /** 一种语言的染色元数据。keywords 为空时退化为注释/字符串/数字三色，照样能看。 */
    data class Lang(
        val id: String,
        val name: String,
        val lineComments: List<String> = emptyList(),
        val blockComment: Pair<String, String>? = null,
        val stringDelims: List<String> = listOf("\""),
        val keywords: Set<String> = emptySet(),
    )

    /** 兜底语言：未知扩展名一律落这里——查看器的"不省略"承诺就落在这行。 */
    val PLAIN = Lang("text", "纯文本", lineComments = listOf("#"))

    private fun lang(
        id: String, name: String, line: String = "#", block: String? = null,
        strs: String = "\"", kw: String = "",
    ): Lang = Lang(
        id, name,
        lineComments = if (line.isBlank()) emptyList() else line.split(' ').filter { it.isNotBlank() },
        blockComment = block?.let { val p = it.split(' '); p[0] to p[1] },
        stringDelims = strs.split(' ').filter { it.isNotBlank() },
        keywords = kw.split(' ').filter { it.isNotBlank() }.toSet(),
    )

    // ---------- 主流语言（关键词组给全） ----------
    private val langs = listOf(
        lang("kotlin", "Kotlin", "//", "/* */", "\" \"\"\"",
            "as break class continue do else false for fun if in interface is null object return super this throw true try typealias val var when while by get set import package data sealed enum open override private public internal protected companion suspend lateinit const vararg out reified inline crossinline noinline operator infix external transient volatile where abstract final late"),
        lang("java", "Java", "//", "/* */", "\" '",
            "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record sealed permit yield true false null"),
        lang("python", "Python", "#", null, "\" '",
            "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield match case self"),
        lang("rust", "Rust", "//", "/* */", "\"",
            "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while"),
        lang("go", "Go", "//", "/* */", "\" `",
            "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false iota"),
        lang("c", "C", "//", "/* */", "\" '",
            "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while NULL true false"),
        lang("cpp", "C++", "//", "/* */", "\" '",
            "alignas alignof and auto bool break case catch char char8_t char16_t char32_t class concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not nullptr operator or private protected public register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch template this throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while override final"),
        lang("csharp", "C#", "//", "/* */", "\" '",
            "abstract as async await base bool break byte case catch char checked class const continue decimal default delegate do double dynamic else enum event explicit extern false finally fixed float for foreach get goto if implicit in init int interface internal is lock long namespace new null object operator out override params partial private protected public readonly record ref return sbyte sealed set short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile when where while yield"),
        lang("javascript", "JavaScript", "//", "/* */", "\" ' `",
            "async await break case catch class const continue debugger default delete do else enum export extends false finally for from function get if import in instanceof let new null of return set static super switch this throw true try typeof undefined var void while with yield"),
        lang("typescript", "TypeScript", "//", "/* */", "\" ' `",
            "abstract any as asserts async await bigint boolean break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new null number object of private protected public readonly return set static string super switch symbol this throw true try type typeof undefined unique unknown var void while with yield"),
        lang("swift", "Swift", "//", "/* */", "\" \"\"\"",
            "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as Any catch false is nil super self throws true try throws await async some each"),
        lang("dart", "Dart", "//", "/* */", "\" '",
            "abstract as assert async await base break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for get hide if implements import in interface is late library mixin new null on operator part required rethrow return sealed set show static super switch sync this throw true try typedef var void while with yield"),
        lang("ruby", "Ruby", "#", null, "\" '",
            "alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require require_relative attr_accessor attr_reader attr_writer puts lambda proc raise new"),
        lang("php", "PHP", "// #", null, "\" '",
            "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null"),
        lang("shell", "Shell", "#", null, "\" '",
            "if then else elif fi for while until do done case esac function in select time coproc local export readonly declare typeset unset shift eval exec exit return trap set echo source alias bind builtin caller cd command compgen complete continue declare dirs disown enable enable fc fg help history jobs kill let logout mapfile popd printf pushd pwd read shopt suspend test type ulimit umask unalias wait"),
        lang("sql", "SQL", "--", "/* */", "\" '",
            "select from where insert into values update set delete create table drop alter add index view database schema join inner left right outer full on group by order having limit offset union all distinct as and or not null primary key foreign references default unique check constraint auto_increment begin commit rollback transaction exists between like in is asc desc count sum avg min max case when then else end with recursive"),
        lang("scala", "Scala", "//", "/* */", "\" \"\"\"",
            "abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new null object override package private protected return sealed super this throw trait true try type val var while with yield given using enum end"),
        lang("lua", "Lua", "--", "--[[ ]]", "\" '",
            "and break do else elseif end false for function goto if in local nil not or repeat return then true until while self pairs ipairs require print"),
        lang("r", "R", "#", null, "\" '",
            "if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA NA_integer_ NA_real_ NA_character_ NA_complex_ library require"),
    )

    // ---------- 其余语言（注释/字符串染色，关键词从简） ----------
    private val others = listOf(
        lang("yaml", "YAML", "#", null, "\" '"),
        lang("toml", "TOML", "#", null, "\" '"),
        lang("ini", "INI/配置", "# ;", null, "\" '"),
        lang("json", "JSON", "", null, "\""),
        lang("jupyter", "Jupyter 笔记本", "", null, "\""),
        lang("xml", "XML", "", "<!-- -->", "\" '"),
        lang("html", "HTML", "", "<!-- -->", "\" '"),
        lang("css", "CSS", "", "/* */", "\" '"),
        lang("scss", "SCSS/Sass", "//", "/* */", "\" '"),
        lang("less", "Less", "//", "/* */", "\" '"),
        lang("markdown", "Markdown", "", null, "\""),
        lang("tex", "TeX/LaTeX", "%", null, "\" '"),
        lang("diff", "Diff/Patch", "", null, "\""),
        lang("log", "日志", "", null, "\" '"),
        lang("csv", "CSV/TSV", "#", null, "\" '"),
        lang("properties", "Properties", "# !", null, "\" '"),
        lang("dockerfile", "Dockerfile", "#", null, "\" '"),
        lang("makefile", "Makefile", "#", null, "\" '"),
        lang("cmake", "CMake", "#", null, "\" \""),
        lang("gradle", "Gradle", "//", "/* */", "\" '"),
        lang("bazel", "Bazel/Starlark", "#", null, "\" '"),
        lang("terraform", "Terraform/HCL", "#", null, "\" '"),
        lang("groovy", "Groovy/Jenkinsfile", "//", "/* */", "\" ' \"\"\""),
        lang("objective", "Objective-C", "//", "/* */", "\" '"),
        lang("perl", "Perl", "#", null, "\" '"),
        lang("haskell", "Haskell", "--", "{- -}", "\""),
        lang("elixir", "Elixir", "#", null, "\" '\"\"\""),
        lang("erlang", "Erlang", "%", null, "\""),
        lang("ocaml", "OCaml/Reason", "", "(* *)", "\""),
        lang("fsharp", "F#", "//", "(* *)", "\" '\"\"\""),
        lang("clojure", "Clojure", ";", null, "\""),
        lang("lisp", "Lisp/Scheme/Racket", ";", null, "\""),
        lang("zig", "Zig", "//", null, "\" '"),
        lang("nim", "Nim", "#", "#[ ]#", "\" '\"\"\""),
        lang("crystal", "Crystal", "#", null, "\" '\"\"\""),
        lang("julia", "Julia", "#", "#= =#", "\" '\"\"\""),
        lang("haxe", "Haxe", "//", "/* */", "\" '"),
        lang("elm", "Elm", "--", "{- -}", "\""),
        lang("purescript", "PureScript", "--", "{- -}", "\""),
        lang("fortran", "Fortran", "!", null, "'"),
        lang("cobol", "COBOL", "*>", null, "\" '"),
        lang("pascal", "Pascal/Delphi", "//", "{ }", "\" '"),
        lang("ada", "Ada", "--", null, "\""),
        lang("asm", "汇编", ";", null, "\" '"),
        lang("verilog", "Verilog/SystemVerilog", "//", "/* */", "\""),
        lang("vhdl", "VHDL", "--", null, "\""),
        lang("cuda", "CUDA", "//", "/* */", "\" '"),
        lang("glsl", "GLSL/着色器", "//", "/* */", "\" '"),
        lang("hlsl", "HLSL", "//", "/* */", "\" '"),
        lang("metal", "Metal", "//", "/* */", "\" '"),
        lang("solidity", "Solidity", "//", "/* */", "\" '"),
        lang("graphql", "GraphQL", "#", null, "\""),
        lang("protobuf", "Protobuf", "//", "/* */", "\" '"),
        lang("thrift", "Thrift", "#", null, "\" '"),
        lang("vue", "Vue 单文件", "", "<!-- -->", "\" ' `"),
        lang("svelte", "Svelte", "", "<!-- -->", "\" ' `"),
        lang("astro", "Astro", "", "<!-- -->", "\" ' `"),
        lang("jsx", "JSX/模板", "//", "/* */", "\" ' `"),
        lang("twig", "Twig 模板", "", "{# #}", "\" '"),
        lang("jinja", "Jinja 模板", "", "{# #}", "\" '"),
        lang("ejs", "EJS 模板", "//", "/* */", "\" '"),
        lang("haml", "Haml", "-#", null, "\" '"),
        lang("slim", "Slim", "/", null, "\" '"),
        lang("pug", "Pug/Jade", "//-", null, "\" '"),
        lang("jsp", "JSP", "", "<%-- --%>", "\" '"),
        lang("razor", "Razor", "//", "@* *@", "\" '"),
        lang("plantuml", "PlantUML", "'", "/' '/", "\""),
        lang("mermaid", "Mermaid 图", "%%", null, "\""),
        lang("vim", "Vim 脚本", "\"", null, "'"),
        lang("powershell", "PowerShell", "#", "<# #>", "\" '"),
        lang("batch", "批处理", "rem ::", null, "\""),
        lang("applescript", "AppleScript", "--", "(* *)", "\""),
        lang("autohotkey", "AutoHotkey", ";", "/* */", "\" '"),
        lang("autoit", "AutoIt", ";", "#cs #ce", "\" '"),
        lang("nix", "Nix", "#", "/* */", "\" '"),
        lang("proto3", "Proto", "//", "/* */", "\" '"),
        lang("ipynb", "Jupyter 笔记本", "", null, "\""),
        lang("binary", "二进制", "", null, ""),
    )

    private val byId = (langs + others).associateBy { it.id }

    /**
     * 扩展名全量表：一行一族（语言 id: 扩展名列表），不写"支持列表"那样的话——
     * 表管认得出的，认不出的全落 [PLAIN]，任何文件都看得了。
     */
    private val EXT_TABLE = """
        kotlin:kt kts ktm;java:java;python:py pyw pyi py3;rust:rs;go:go
        c:c h;cpp:cpp cc cxx c++ hpp hh hxx h++ ipp tcc inl;cuda:cu cuh
        csharp:cs csx;swift:swift;dart:dart;javascript:js mjs cjs jsx
        typescript:ts tsx mts cts;ruby:rb rbw erb rake gemspec
        php:php phtml php3 php4 php5 php7 phps;shell:sh bash zsh fish ksh csh
        powershell:ps1 psm1 psd1;batch:bat cmd;sql:sql ddl dml
        scala:sc scala;groovy:gradle groovy gvy
        haskell:hs lhs idr;elixir:ex exs;erlang:erl hrl;ocaml:ml mli
        fsharp:fs fsi fsx fsscript;clojure:clj cljs cljc edn
        lisp:lisp lsp el scm ss rkt cl;zig:zig;nim:nim;crystal:cr;julia:jl
        lean:lean;verilog:sv svh vh;coq:v
        objective:m mm;perl:pl pm t pod;lua:lua;r:r R Rd
        fortran:f for ftn f90 f95 f03 f08;cobol:cbl cob cpy;ada:ada adb ads
        pascal:pas pp dpr dpk lpr
        vhdl:vhd vhdl vho vht;asm:asm s nasm inc
        glsl:glsl vert frag geom comp tesc tese vs fs;hlsl:hlsl hlsli fx fxh
        metal:metal;solidity:sol;cairo:cairo;move:move
        yaml:yml yaml;toml:toml;ini:ini cfg conf config props
        json:json jsonc json5;xml:xml xsl xslt xsd dtd plist pom svg xaml axml
        html:html htm xhtml mht;css:css;scss:scss sass;less:less styl
        markdown:md markdown mdx mkd;tex:tex latex sty cls dtx ltx
        diff:diff patch;log:log logtxt;csv:csv tsv;rst:rst;adoc:adoc asciidoc
        properties:properties env;graphql:graphql gql;protobuf:proto
        thrift:thrift;avro:avsc;vim:vim vimrc
        cmake:cmake;bazel:bzl;terraform:tf tfvars hcl
        vue:vue;svelte:svelte;astro:astro;twig:twig;jinja:jinja j2 jinja2
        ejs:ejs;haml:haml;slim:slim;pug:pug jade;jsp:jsp jspx tag
        razor:cshtml razor vbhtml;plantuml:puml iuml uml;mermaid:mmd
        haxe:hx;elm:elm;purescript:purs;reason:re rei;rescript:res resi
        nix:nix;applescript:applescript scpt;autohotkey:ahk ahkl;autoit:au3
        shader:shader shaderlab cginc;wgsl:wgsl;jupyter:ipynb
    """.trimIndent()

    /** 文件名全量表（无扩展名的常见构建/仓库文件）。 */
    private val NAME_TABLE = mapOf(
        "dockerfile" to "dockerfile", "makefile" to "makefile", "gnumakefile" to "makefile",
        "jenkinsfile" to "groovy", "gemfile" to "ruby", "rakefile" to "ruby", "podfile" to "ruby",
        "brewfile" to "ruby", "vagrantfile" to "ruby", "fastfile" to "groovy", "appfile" to "groovy",
        "build" to "bazel", "workspace" to "bazel", "cmakelists.txt" to "cmake",
        "license" to "PLAIN", "readme" to "markdown", "changelog" to "markdown",
        "notice" to "PLAIN", "authors" to "PLAIN", "contributors" to "PLAIN",
        ".gitignore" to "PLAIN", ".gitattributes" to "PLAIN", ".editorconfig" to "INI",
        ".gitmodules" to "INI", ".env" to "properties", ".babelrc" to "json",
        ".eslintrc" to "json", ".prettierrc" to "json", "gradlew" to "shell",
        "go.mod" to "go", "go.sum" to "PLAIN", "cargo.lock" to "toml",
        "package.json" to "json", "pubspec.yaml" to "yaml", "gemfile.lock" to "PLAIN",
    )

    /** 扩展名到语言 id 的账（小写扩展名）。 */
    private val extMap: Map<String, String> = EXT_TABLE.split('\n').flatMap { line ->
        line.split(';').mapNotNull { group ->
            val parts = group.trim().split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].trim().removePrefix("haskell2").let { aliasFix(it) }
            val exts = parts[1].trim().split(Regex("\\s+"))
            exts.filter { it.isNotBlank() }.map { it.lowercase().trimStart('.') to id }
        }.flatten()
    }.toMap()

    /** 表内个别组起了占位名，这里归到真语言。 */
    private fun aliasFix(id: String): String = when (id) {
        "idris" -> "haskell"
        "haskell3" -> "haskell"
        "agda2" -> "haskell"
        "proto3" -> "protobuf"
        "haskell3b" -> "haskell"
        else -> id
    }

    /** 按扩展名找语言：认得就给对应语言，认不得给 [PLAIN]——没有"不支持"。 */
    fun byExtension(extRaw: String): Lang {
        val ext = extRaw.trim().lowercase().trimStart('.')
        val id = extMap[ext] ?: return PLAIN
        return if (id == "PLAIN") PLAIN else byId[id] ?: PLAIN
    }

    /** 按文件名找语言（无扩展名场景：Dockerfile/Makefile/README 们）。 */
    fun byFileName(name: String): Lang {
        val lower = name.trim().lowercase()
        NAME_TABLE[lower]?.let { return if (it == "PLAIN") PLAIN else byId[it] ?: PLAIN }
        val dot = lower.lastIndexOf('.')
        return if (dot >= 0) byExtension(lower.substring(dot + 1)) else PLAIN
    }

    /** 调试/展示用：当前表里登记了多少个扩展名。 */
    fun extCount(): Int = extMap.size

    fun allLangs(): List<Lang> = langs + others
}
