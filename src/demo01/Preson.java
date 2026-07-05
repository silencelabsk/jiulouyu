package demo01;

public class Preson {

    String nanme;
    int age;

    public Preson() {

    }

    public void show(){
         System.out.println("我叫："+ nanme +","+ "年龄："+ age);

    }

    public int getAge() {
        return age;
    }

    public Preson(String nanme, int age) {
        this.nanme = nanme;
        this.age = age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getNanme() {
        return nanme;
    }

    public void setNanme(String nanme) {
        this.nanme = nanme;
    }
}
